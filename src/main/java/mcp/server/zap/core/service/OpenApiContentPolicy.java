package mcp.server.zap.core.service;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounds and confines a self-contained OpenAPI document before staging it for ZAP.
 * This intentionally supports a restricted import subset, not general JSON Schema
 * resolution: YAML aliases, schema identifiers/dialects, callback/webhook operations,
 * Link operation references/server overrides and remotely loaded examples are rejected.
 * Documentation URLs and literal example/default data are not treated as references.
 */
public final class OpenApiContentPolicy {
    static final int MAX_INPUT_BYTES = 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    static final int MAX_NODES = 10_000;
    static final int MAX_DEPTH = 50;
    static final int MAX_SCALAR_CHARS = 1024 * 1024;
    static final int MAX_OPERATIONS = 1_000;
    private static final int MAX_TARGET_CHARS = 8_192;
    private static final int MAX_NUMBER_CHARS = 1_000;
    private static final String FIELD_SERVERS = "servers";
    private static final String FIELD_SCHEMES = "schemes";
    private static final String FIELD_PARAMETERS = "parameters";
    private static final String FIELD_EXAMPLES = "examples";
    private static final String UNRESOLVED_REFERENCE_MESSAGE = "Unresolved internal OpenAPI reference";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> SCHEMA_IDENTIFIERS = Set.of(
            "$id", "id", "$schema", "$dynamicRef", "$anchor", "$dynamicAnchor", "$recursiveRef", "$recursiveAnchor");
    private static final Set<String> SCHEMA_MAPS = Set.of("properties", "patternProperties", "$defs", "definitions", "dependentSchemas");
    private static final Set<String> SCHEMA_VALUES = Set.of(
            "items", "additionalItems", "contains", "additionalProperties", "propertyNames", "unevaluatedItems",
            "unevaluatedProperties", "not", "if", "then", "else", "contentSchema");
    private static final Set<String> SCHEMA_LISTS = Set.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final Set<String> LITERAL_DATA = Set.of("example", "default", "enum", "const", "value");
    private static final Set<String> NAMED_OBJECTS = Set.of(
            "paths", "responses", "headers", "content", "links", "requestBodies", "securitySchemes", "pathItems", "encoding");
    private static final Pattern TEMPLATE = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UrlValidationService urlValidationService;

    public OpenApiContentPolicy(UrlValidationService urlValidationService) {
        this.urlValidationService = Objects.requireNonNull(urlValidationService, "urlValidationService");
    }

    /** Returns normalized, bounded JSON; never returns the original document text. */
    public byte[] prepare(String content, String fullTargetUrl) {
        validateInputSize(content);
        if (content == null || content.isBlank()) {
            throw invalid("OpenAPI content cannot be blank");
        }
        String target = validateTarget(fullTargetUrl);
        Map<String, Object> document = parse(content);
        boolean modern = validateDocumentHeader(document);
        Map<String, Object> paths = object(document.get("paths"), "OpenAPI paths must be an object");
        new DocumentWalker(document, modern, target).visit(document);
        rewriteDocumentTarget(document, modern, target);
        validateOperations(document, paths, modern, target);
        // Rewriting servers can invalidate references into the former server arrays.
        new DocumentWalker(document, modern, target).visit(document);
        return normalizedJson(document);
    }

    private static boolean validateDocumentHeader(Map<String, Object> document) {
        Object openapi = document.get("openapi");
        Object swagger = document.get("swagger");
        boolean modern = openapi instanceof String version && version.matches("3\\.[01]\\.\\d+");
        boolean legacy = "2.0".equals(swagger);
        if (modern == legacy || (modern && swagger != null) || (legacy && openapi != null)) {
            throw invalid("Supported versions are Swagger 2.0 and OpenAPI 3.0.x or 3.1.x");
        }
        if (document.containsKey("jsonSchemaDialect")) {
            throw invalid("Custom OpenAPI schema dialects are not supported");
        }
        Map<String, Object> info = object(document.get("info"), "OpenAPI info must be an object");
        if (!(info.get("title") instanceof String title) || title.isBlank()
                || !(info.get("version") instanceof String version) || version.isBlank()) {
            throw invalid("OpenAPI info requires a title and version");
        }
        return modern;
    }

    private static void rewriteDocumentTarget(Map<String, Object> document, boolean modern, String target) {
        if (modern) {
            document.put(FIELD_SERVERS, selectedServer(target));
        } else {
            URI uri = URI.create(target);
            document.put("host", uri.getRawAuthority());
            document.put(FIELD_SCHEMES, List.of(uri.getScheme()));
            document.put("basePath", uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        }
    }

    private void validateOperations(Map<String, Object> document, Map<String, Object> paths, boolean modern, String target) {
        String operationBase = withoutTrailingSlashes(target);
        int operations = 0;
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            if (entry.getKey().startsWith("x-")) {
                continue;
            }
            operations = validatePathItem(document, entry, modern, target, operationBase, operations);
        }
    }

    private int validatePathItem(Map<String, Object> document, Map.Entry<String, Object> entry,
            boolean modern, String target, String operationBase, int operations) {
        String requestPath = validateOperationPath(entry.getKey());
        Map<String, Object> pathItem = resolveObject(document, entry.getValue(), newIdentitySet());
        if (modern) {
            object(entry.getValue(), "OpenAPI path items must be objects").put(FIELD_SERVERS, selectedServer(target));
        }
        String operationUrl = operationBase + requestPath;
        for (Map.Entry<String, Object> field : pathItem.entrySet()) {
            if (!METHODS.contains(field.getKey())) {
                if (!Set.of("$ref", "summary", "description", FIELD_SERVERS, FIELD_PARAMETERS).contains(field.getKey())
                        && !field.getKey().startsWith("x-")) {
                    throw invalid("Unsupported OpenAPI path item field");
                }
                continue;
            }
            if (++operations > MAX_OPERATIONS) {
                throw invalid("OpenAPI content exceeds the operation limit");
            }
            validateOperation(document, pathItem, field.getValue(), modern, target, operationUrl);
        }
        return operations;
    }

    private void validateOperation(Map<String, Object> document, Map<String, Object> pathItem, Object rawOperation,
            boolean modern, String target, String operationUrl) {
        Map<String, Object> operation = object(rawOperation, "OpenAPI operations must be objects");
        if (modern) {
            operation.put(FIELD_SERVERS, selectedServer(target));
        } else if (operation.containsKey(FIELD_SCHEMES)) {
            operation.put(FIELD_SCHEMES, List.of(URI.create(target).getScheme()));
        }
        validatePathParameters(document, pathItem.get(FIELD_PARAMETERS));
        validatePathParameters(document, operation.get(FIELD_PARAMETERS));
        try {
            validatePolicy(URI.create(operationUrl).toASCIIString());
        } catch (IllegalArgumentException e) {
            throw invalid("OpenAPI operation target is invalid or prohibited");
        }
    }

    private static String withoutTrailingSlashes(String target) {
        int end = target.length();
        while (end > 0 && target.charAt(end - 1) == '/') {
            end--;
        }
        return target.substring(0, end);
    }

    private static byte[] normalizedJson(Map<String, Object> document) {
        new ObjectBudget().visit(document, 1);
        BoundedOutput output = new BoundedOutput();
        try {
            JSON.writeValue(output, document);
        } catch (Exception e) {
            throw invalid(output.exceeded ? "Normalized OpenAPI content exceeds the output limit" : "Unable to normalize OpenAPI content");
        }
        return output.toByteArray();
    }

    private Map<String, Object> parse(String content) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_INPUT_BYTES);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setMaxAliasesForCollections(0);
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMergeOnCompose(false);
        try {
            Node root = new Yaml(new SafeConstructor(options)).compose(new StringReader(content));
            if (!(root instanceof MappingNode)) {
                throw invalid("OpenAPI content must be an object");
            }
            new NodeBudget().visit(root, 1);
            return object(construct(root, new ScalarConstructor(options)), "OpenAPI content must be an object");
        } catch (YAMLException | NumberFormatException e) {
            // Parser diagnostics can contain submitted text, source snippets and credentials.
            throw invalid("Invalid or unsupported OpenAPI JSON/YAML content");
        }
    }

    private Object construct(Node node, ScalarConstructor constructor) {
        if (node instanceof MappingNode mapping) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (NodeTuple tuple : mapping.getValue()) {
                result.put(((ScalarNode) tuple.getKeyNode()).getValue(), construct(tuple.getValueNode(), constructor));
            }
            return result;
        }
        if (node instanceof SequenceNode sequence) {
            List<Object> result = new ArrayList<>();
            for (Node child : sequence.getValue()) {
                result.add(construct(child, constructor));
            }
            return result;
        }
        Object scalar = constructor.scalar((ScalarNode) node);
        if (scalar instanceof Double number && !Double.isFinite(number)) {
            throw invalid("OpenAPI content requires finite JSON numbers");
        }
        return scalar;
    }

    private String validateTarget(String input) {
        if (input == null || input.isBlank() || input.length() > MAX_TARGET_CHARS) {
            throw invalid("A bounded full HTTP(S) API target is required");
        }
        String target = input.trim();
        try {
            URI uri = URI.create(target);
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank() || uri.getHost().endsWith(".")
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw invalid("Invalid API target");
            }
            validatePath(uri.getRawPath(), false);
            String canonical = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + uri.getRawPath();
            canonical = URI.create(canonical).toASCIIString();
            validatePolicy(canonical);
            return canonical;
        } catch (IllegalArgumentException e) {
            throw invalid("A permitted full HTTP(S) API target without credentials, query, fragment or traversal is required");
        }
    }

    private void validatePolicy(String target) {
        try {
            urlValidationService.validateUrl(target);
        } catch (RuntimeException e) {
            throw invalid("OpenAPI target is prohibited by URL policy");
        }
    }

    private String validateOperationPath(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw invalid("OpenAPI operation paths must be relative paths beginning with one slash");
        }
        validatePath(path, true);
        String result = TEMPLATE.matcher(path).replaceAll("openapi-value");
        if (result.contains("{") || result.contains("}")) {
            throw invalid("Unsupported OpenAPI path template");
        }
        return result;
    }

    private static void validatePath(String path, boolean templates) {
        if (path == null || path.indexOf('\\') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.contains("://") || path.contains("//") || path.chars().anyMatch(character -> character <= 0x20 || character == 0x7f)) {
            throw invalid("Unsafe OpenAPI target or operation path");
        }
        String decoded = decodePath(path);
        if (decoded.indexOf('\\') >= 0 || decoded.indexOf('?') >= 0 || decoded.indexOf('#') >= 0
                || decoded.contains("://") || decoded.contains("%") || decoded.contains("//")
                || (!templates && (decoded.contains("{") || decoded.contains("}")))) {
            throw invalid("Unsafe encoded OpenAPI path");
        }
        for (String segment : decoded.split("/", -1)) {
            String unparameterized = segment.split(";", 2)[0];
            if (".".equals(unparameterized) || "..".equals(unparameterized)) {
                throw invalid("OpenAPI paths cannot contain dot segments");
            }
        }
    }

    private static String decodePath(String path) {
        StringBuilder decoded = new StringBuilder(path.length());
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '%') {
                if (index + 2 >= path.length()) {
                    throw invalid("Invalid encoded OpenAPI path");
                }
                int high = Character.digit(path.charAt(index + 1), 16);
                int low = Character.digit(path.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw invalid("Invalid encoded OpenAPI path");
                }
                character = (char) ((high << 4) | low);
                if (character == '/' || character == '\\' || character == '{' || character == '}'
                        || character <= 0x20 || character == 0x7f) {
                    throw invalid("Encoded path separators and controls are not supported");
                }
                index += 2;
            }
            decoded.append(character);
        }
        return decoded.toString();
    }

    private void validatePathParameters(Map<String, Object> document, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> parameters)) {
            throw invalid("OpenAPI parameters must be arrays");
        }
        for (Object raw : parameters) {
            Map<String, Object> parameter = resolveObject(document, raw, newIdentitySet());
            if ("path".equals(parameter.get("in"))) {
                if (parameter.containsKey("content")) {
                    throw invalid("Content-valued OpenAPI path parameters are not supported");
                }
                if (Boolean.TRUE.equals(parameter.get("allowReserved"))) {
                    throw invalid("Reserved path parameter expansion is not supported");
                }
                validateParameterSamples(document, parameter, newIdentitySet(), 0);
            }
        }
    }

    private void validateParameterSamples(Map<String, Object> document, Object value, Set<Object> visited, int referenceDepth) {
        if (!(value instanceof Map<?, ?>) || !visited.add(value)) {
            return;
        }
        Map<String, Object> map = object(value, "Invalid path parameter schema");
        Object type = map.get("type");
        if ("object".equals(type) || type instanceof List<?> types && types.contains("object")
                || map.containsKey("properties") || map.containsKey("additionalProperties")
                || map.containsKey("patternProperties")) {
            throw invalid("Object-valued OpenAPI path parameters are not supported");
        }
        if (map.containsKey("$ref")) {
            requireReferenceDepth(referenceDepth + 1);
            validateParameterSamples(document, pointer(document, map.get("$ref")), visited, referenceDepth + 1);
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (FIELD_EXAMPLES.equals(entry.getKey()) && entry.getValue() instanceof Map<?, ?> examples) {
                for (Object rawExample : examples.values()) {
                    Map<String, Object> example = resolveObject(document, rawExample, newIdentitySet());
                    validateSample(example.get("value"));
                }
            } else if (LITERAL_DATA.contains(entry.getKey()) || FIELD_EXAMPLES.equals(entry.getKey())) {
                validateSample(entry.getValue());
            } else if ("schema".equals(entry.getKey()) || SCHEMA_VALUES.contains(entry.getKey())) {
                validateParameterSamples(document, entry.getValue(), visited, referenceDepth);
            } else if (SCHEMA_LISTS.contains(entry.getKey()) && entry.getValue() instanceof List<?> list) {
                list.forEach(item -> validateParameterSamples(document, item, visited, referenceDepth));
            }
        }
    }

    private static void validateSample(Object sample) {
        if (sample instanceof String text) {
            String decoded = decodePath(text);
            if (decoded.contains("/") || decoded.contains("\\") || decoded.contains("?") || decoded.contains("#")
                    || decoded.contains("%") || decoded.contains("{") || decoded.contains("}")
                    || ".".equals(decoded.split(";", 2)[0]) || "..".equals(decoded.split(";", 2)[0])) {
                throw invalid("Unsafe OpenAPI path parameter sample");
            }
        } else if (sample instanceof List<?> list) {
            list.forEach(OpenApiContentPolicy::validateSample);
        } else if (sample instanceof Map<?, ?>) {
            throw invalid("Object-valued OpenAPI path parameter samples are not supported");
        }
    }

    private static Map<String, Object> resolveObject(Map<String, Object> document, Object raw, Set<Object> visited) {
        Map<String, Object> value = object(raw, "OpenAPI path items and parameters must be objects");
        requireReferenceDepth(visited.size() + 1);
        if (!visited.add(value)) {
            throw invalid("Cyclic OpenAPI path item or parameter references are not supported");
        }
        if (!value.containsKey("$ref")) {
            return value;
        }
        Map<String, Object> resolved = new LinkedHashMap<>(resolveObject(document, pointer(document, value.get("$ref")), visited));
        resolved.putAll(value);
        return resolved;
    }

    private static void requireReferenceDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("OpenAPI content exceeds the internal reference chain limit");
        }
    }

    private static Object pointer(Map<String, Object> document, Object rawReference) {
        if (!(rawReference instanceof String reference) || (!reference.equals("#") && !reference.startsWith("#/"))) {
            throw invalid("Only internal OpenAPI JSON Pointer references are supported");
        }
        String fragment;
        try {
            URI uri = URI.create(reference);
            if (uri.getRawFragment() == null || !uri.toString().equals(reference)) {
                throw invalid("Invalid internal OpenAPI reference");
            }
            fragment = uri.getFragment();
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid internal OpenAPI reference");
        }
        if (fragment.isEmpty()) {
            return document;
        }
        if (!fragment.startsWith("/")) {
            throw invalid("Only internal OpenAPI JSON Pointer references are supported");
        }
        Object current = document;
        for (String token : fragment.substring(1).split("/", -1)) {
            for (int index = 0; index < token.length(); index++) {
                if (token.charAt(index) == '~' && (index + 1 >= token.length()
                        || (token.charAt(index + 1) != '0' && token.charAt(index + 1) != '1'))) {
                    throw invalid("Invalid internal OpenAPI JSON Pointer escape");
                }
                if (token.charAt(index) == '~') {
                    index++;
                }
            }
            token = token.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map && map.containsKey(token)) {
                current = map.get(token);
            } else if (current instanceof List<?> list && token.matches("0|[1-9][0-9]*")) {
                try {
                    int index = Integer.parseInt(token);
                    if (index >= list.size()) {
                        throw invalid(UNRESOLVED_REFERENCE_MESSAGE);
                    }
                    current = list.get(index);
                } catch (NumberFormatException e) {
                    throw invalid(UNRESOLVED_REFERENCE_MESSAGE);
                }
            } else {
                throw invalid(UNRESOLVED_REFERENCE_MESSAGE);
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String message) {
        if (!(value instanceof Map<?, ?>)) {
            throw invalid(message);
        }
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> selectedServer(String target) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", target);
        return new ArrayList<>(List.of(server));
    }

    private static <T> Set<T> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static void validateInputSize(String content) {
        if (content == null) {
            return;
        }
        if (content.length() > MAX_INPUT_BYTES) {
            throw invalid("OpenAPI content exceeds the UTF-8 input limit");
        }
        int bytes = 0;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= content.length() || !Character.isLowSurrogate(content.charAt(++index))) {
                    throw invalid("OpenAPI content must contain valid Unicode");
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw invalid("OpenAPI content must contain valid Unicode");
            } else {
                bytes += character <= 0x7f ? 1 : character <= 0x7ff ? 2 : 3;
            }
            if (bytes > MAX_INPUT_BYTES) {
                throw invalid("OpenAPI content exceeds the UTF-8 input limit");
            }
        }
    }

    private static final class DocumentWalker {
        private final Map<String, Object> document;
        private final boolean modern;
        private final String target;
        private final Map<Object, Integer> visitedObjects = new IdentityHashMap<>();
        private final Map<Object, Integer> visitedSchemas = new IdentityHashMap<>();
        private final Set<Object> activeSchemas = newIdentitySet();
        private final Set<Object> activeReferences = newIdentitySet();
        private int referenceDepth;
        private int traversalDepth;

        private DocumentWalker(Map<String, Object> document, boolean modern, String target) {
            this.document = document;
            this.modern = modern;
            this.target = target;
        }

        private void visit(Object value) {
            if (!(value instanceof Map<?, ?> || value instanceof List<?>)) {
                return;
            }
            if (activeReferences.contains(value)) {
                throw invalid("Cyclic non-schema OpenAPI references are not supported");
            }
            requireTraversalDepth(++traversalDepth);
            try {
                // A suffix validated from an earlier, shallower entry point does
                // not prove the same suffix is safe at a deeper reference level.
                if (visitedObjects.getOrDefault(value, -1) >= referenceDepth) {
                    return;
                }
                visitedObjects.put(value, referenceDepth);
                visitChecked(value);
            } finally {
                traversalDepth--;
            }
        }

        private void visitChecked(Object value) {
            if (value instanceof List<?> list) {
                list.forEach(this::visit);
                return;
            }
            Map<String, Object> map = object(value, "Invalid OpenAPI object");
            followObjectReference(map);
            if (map.containsKey("externalValue") || map.containsKey("operationRef") || map.containsKey("server")) {
                throw invalid("External examples, Link operation references and request server overrides are not supported");
            }
            if (modern && map.containsKey(FIELD_SERVERS)) {
                map.put(FIELD_SERVERS, selectedServer(target));
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                visitMember(entry.getKey(), entry.getValue());
            }
        }

        private void followObjectReference(Map<String, Object> map) {
            if (!map.containsKey("$ref")) {
                return;
            }
            requireReferenceDepth(referenceDepth + 1);
            referenceDepth++;
            activeReferences.add(map);
            try {
                visit(pointer(document, map.get("$ref")));
            } finally {
                activeReferences.remove(map);
                referenceDepth--;
            }
        }

        private void visitMember(String name, Object value) {
            if (LITERAL_DATA.contains(name) || (FIELD_EXAMPLES.equals(name) && (!modern || value instanceof List<?>))) {
                return;
            }
            if (("callbacks".equals(name) || "webhooks".equals(name))
                    && (!(value instanceof Map<?, ?> callbacks) || !callbacks.isEmpty())) {
                throw invalid("OpenAPI callbacks and webhooks are not supported for content import");
            }
            if ("schema".equals(name)) {
                schema(value);
                return;
            }
            if ("schemas".equals(name) || "definitions".equals(name)) {
                object(value, "OpenAPI schemas must be objects").values().forEach(this::schema);
                return;
            }
            if (NAMED_OBJECTS.contains(name) || FIELD_EXAMPLES.equals(name)
                    || (FIELD_PARAMETERS.equals(name) && value instanceof Map<?, ?>)) {
                object(value, "OpenAPI named members must be objects").values().forEach(this::visit);
                return;
            }
            visit(value);
        }

        private void schema(Object value) {
            if (value instanceof Boolean) {
                return;
            }
            Map<String, Object> map = object(value, "OpenAPI schemas must be objects or booleans");
            if (activeSchemas.contains(map)) {
                return;
            }
            requireTraversalDepth(++traversalDepth);
            try {
                if (visitedSchemas.getOrDefault(map, -1) >= referenceDepth) {
                    return;
                }
                visitedSchemas.put(map, referenceDepth);
                activeSchemas.add(map);
                schemaChecked(map);
            } finally {
                activeSchemas.remove(map);
                traversalDepth--;
            }
        }

        private void requireTraversalDepth(int depth) {
            if (depth > MAX_DEPTH * 2) {
                throw invalid("OpenAPI content exceeds the internal reference traversal nesting limit");
            }
        }

        private void schemaChecked(Map<String, Object> map) {
            if (map.keySet().stream().anyMatch(SCHEMA_IDENTIFIERS::contains)) {
                throw invalid("Schema identifiers, anchors and dialect rebasing are not supported");
            }
            if (map.containsKey("$ref")) {
                followSchemaReference(map.get("$ref"));
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                visitSchemaMember(entry.getKey(), entry.getValue());
            }
        }

        private void followSchemaReference(Object reference) {
            requireReferenceDepth(referenceDepth + 1);
            referenceDepth++;
            try {
                schema(pointer(document, reference));
            } finally {
                referenceDepth--;
            }
        }

        private void visitSchemaMember(String name, Object value) {
            if (LITERAL_DATA.contains(name) || FIELD_EXAMPLES.equals(name)) {
                return;
            }
            if (SCHEMA_MAPS.contains(name)) {
                object(value, "Schema members must be objects").values().forEach(this::schema);
                return;
            }
            if ("discriminator".equals(name) && value instanceof Map<?, ?>) {
                visitDiscriminatorMappings(value);
                return;
            }
            if (SCHEMA_VALUES.contains(name)) {
                if (value instanceof List<?> list) {
                    list.forEach(this::schema);
                } else {
                    schema(value);
                }
                return;
            }
            if (SCHEMA_LISTS.contains(name)) {
                if (!(value instanceof List<?> list)) {
                    throw invalid("Schema alternatives must be arrays");
                }
                list.forEach(this::schema);
                return;
            }
            visit(value);
        }

        private void visitDiscriminatorMappings(Object value) {
            Map<String, Object> discriminator = object(value, "Invalid OpenAPI discriminator");
            Object rawMappings = discriminator.get("mapping");
            if (rawMappings == null) {
                return;
            }
            for (Object reference : object(rawMappings, "Discriminator mappings must be objects").values()) {
                followSchemaReference(reference);
            }
        }
    }

    private static final class ScalarConstructor extends SafeConstructor {
        private ScalarConstructor(LoaderOptions options) {
            super(options);
        }

        private Object scalar(ScalarNode node) {
            return constructObject(node);
        }
    }

    private static final class NodeBudget {
        private final Set<Node> seen = newIdentitySet();
        private int nodes;
        private long chars;

        private void visit(Node node, int depth) {
            if (!seen.add(node) || node.getAnchor() != null) {
                throw invalid("YAML anchors and aliases are not supported for OpenAPI content");
            }
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
                throw invalid("OpenAPI content exceeds the node or nesting limit");
            }
            if (node instanceof MappingNode mapping && Tag.MAP.equals(node.getTag())) {
                Set<String> keys = new LinkedHashSet<>();
                for (NodeTuple tuple : mapping.getValue()) {
                    if (!(tuple.getKeyNode() instanceof ScalarNode key)
                            || (!Tag.STR.equals(key.getTag()) && !Tag.INT.equals(key.getTag()))) {
                        throw invalid("OpenAPI object keys must be text or integer scalars");
                    }
                    if (!keys.add(key.getValue())) {
                        throw invalid("Duplicate OpenAPI object keys are not supported");
                    }
                    visit(key, depth + 1);
                    visit(tuple.getValueNode(), depth + 1);
                }
            } else if (node instanceof SequenceNode sequence && Tag.SEQ.equals(node.getTag())) {
                sequence.getValue().forEach(child -> visit(child, depth + 1));
            } else if (node instanceof ScalarNode scalar && Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL).contains(node.getTag())) {
                if ((Tag.INT.equals(node.getTag()) || Tag.FLOAT.equals(node.getTag()))
                        && scalar.getValue().length() > MAX_NUMBER_CHARS) {
                    throw invalid("OpenAPI numeric values exceed the parser limit");
                }
                chars += scalar.getValue().length();
                if (chars > MAX_SCALAR_CHARS) {
                    throw invalid("OpenAPI content exceeds the scalar limit");
                }
            } else {
                throw invalid("Unsafe or unsupported YAML tags are not allowed");
            }
        }
    }

    private static final class ObjectBudget {
        private int nodes;
        private long chars;

        private void visit(Object value, int depth) {
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
                throw invalid("Normalized OpenAPI content exceeds the node or nesting limit");
            }
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, child) -> {
                    visit(key, depth + 1);
                    visit(child, depth + 1);
                });
            } else if (value instanceof List<?> list) {
                list.forEach(child -> visit(child, depth + 1));
            } else if (value != null) {
                chars += value.toString().length();
                if (chars > MAX_SCALAR_CHARS) {
                    throw invalid("Normalized OpenAPI content exceeds the scalar limit");
                }
            }
        }
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        private boolean exceeded;

        @Override
        public synchronized void write(int value) {
            requireRoom(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            requireRoom(length);
            super.write(bytes, offset, length);
        }

        private void requireRoom(int count) {
            if (count > MAX_OUTPUT_BYTES - size()) {
                exceeded = true;
                throw invalid("Normalized OpenAPI content exceeds the output limit");
            }
        }
    }
}
