package mcp.server.zap.core.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OpenApiContentPolicyTest {
    private static final String TARGET = "https://api.example.com/v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String YAML = """
            openapi: "3.0.3"
            info:
              title: Example
              version: "1"
            paths:
              /pets:
                get:
                  responses:
                    200:
                      description: Okay
            """;
    private UrlValidationService urls;
    private OpenApiContentPolicy policy;

    @BeforeEach
    void setUp() {
        urls = mock(UrlValidationService.class);
        policy = new OpenApiContentPolicy(urls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.0", "3.0.3", "3.1.0", "3.1.2"})
    void normalizesSupportedJsonAndValidatesTheActualOperationDestination(String version) {
        Map<String, Object> input = definition();
        input.put("openapi", version);

        JsonNode result = prepared(input);

        assertThat(result.path("openapi").asString()).isEqualTo(version);
        assertThat(result.path("servers").get(0).path("url").asString()).isEqualTo(TARGET);
        verify(urls).validateUrl(TARGET);
        verify(urls).validateUrl(TARGET + "/pets");
    }

    @Test
    void normalizesYamlWithUnquotedResponseCodesToJsonUtf8() {
        byte[] result = policy.prepare(YAML.replace("Example", "Example 雪"), TARGET);

        assertThat(new String(result, StandardCharsets.UTF_8)).startsWith("{").contains("Example 雪");
        assertThat(JSON.readTree(result).path("paths").path("/pets").path("get")
                .path("responses").has("200")).isTrue();
    }

    @Test
    void rewritesSwaggerRootAndOperationSchemes() {
        Map<String, Object> input = definition();
        input.remove("openapi");
        input.put("swagger", "2.0");
        input.put("host", "unselected.example.com");
        input.put("basePath", "/original");
        input.put("schemes", List.of("http"));
        operation(input).put("schemes", List.of("http"));

        JsonNode result = prepared(input);

        assertThat(result.path("host").asString()).isEqualTo("api.example.com");
        assertThat(result.path("basePath").asString()).isEqualTo("/v1");
        assertThat(result.path("schemes").get(0).asString()).isEqualTo("https");
        assertThat(result.path("paths").path("/pets").path("get").path("schemes").get(0).asString()).isEqualTo("https");
    }

    @Test
    void overwritesRootPathAndOperationServerTemplatesAndVariables() {
        Map<String, Object> input = definition();
        Map<String, Object> hostileServer = Map.of("url", "https://{host}/other", "variables", Map.of("host", Map.of("default", "unselected.example.com")));
        input.put("servers", List.of(hostileServer));
        pathItem(input).put("servers", List.of(hostileServer));
        operation(input).put("servers", List.of(hostileServer));

        JsonNode result = prepared(input);

        assertThat(result.path("servers").get(0).path("url").asString()).isEqualTo(TARGET);
        assertThat(result.path("paths").path("/pets").path("servers").get(0).path("url").asString()).isEqualTo(TARGET);
        assertThat(result.path("paths").path("/pets").path("get").path("servers").get(0).path("url").asString()).isEqualTo(TARGET);
        assertThat(result.toString()).doesNotContain("unselected.example.com", "variables", "{host}");
    }

    @Test
    void acceptsInternalCyclicSchemaReferencesAndPropertyNamesThatResembleKeywords() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of("Node", Map.of(
                "type", "object", "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "$id", Map.of("type", "string"),
                        "next", Map.of("$ref", "#/components/schemas/Node"))))));

        JsonNode result = prepared(input);

        assertThat(result.path("components").path("schemas").path("Node").path("properties")
                .path("next").path("$ref").asString()).isEqualTo("#/components/schemas/Node");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsMutualSchemaCyclesButBoundsLongShallowReferenceChainsInEitherOrder(boolean reverseOrder) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of(
                "A", Map.of("$ref", "#/components/schemas/B"),
                "B", Map.of("$ref", "#/components/schemas/A"))));
        assertThat(prepared(input).path("components").path("schemas").size()).isEqualTo(2);

        Map<String, Object> schemas = new LinkedHashMap<>();
        int count = OpenApiContentPolicy.MAX_DEPTH + 5;
        if (reverseOrder) {
            schemas.put("Node" + count, Map.of("type", "string"));
        }
        for (int position = 0; position < count; position++) {
            int index = reverseOrder ? count - position - 1 : position;
            schemas.put("Node" + index, Map.of("$ref", "#/components/schemas/Node" + (index + 1)));
        }
        if (!reverseOrder) {
            schemas.put("Node" + count, Map.of("type", "string"));
        }
        input.put("components", Map.of("schemas", schemas));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference chain limit");
    }

    @Test
    void boundsCombinedStructuralAndReferenceTraversalNesting() {
        Map<String, Object> input = definition();
        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("Node8", Map.of("type", "string"));
        for (int index = 7; index >= 0; index--) {
            Object nested = Map.of("$ref", "#/components/schemas/Node" + (index + 1));
            for (int layer = 0; layer < 15; layer++) {
                nested = Map.of("type", "object", "properties", Map.of("child", nested));
            }
            schemas.put("Node" + index, nested);
        }
        input.put("components", Map.of("schemas", schemas));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal nesting limit");
    }

    @Test
    void acceptsJsonPointerEscapesAndPercentEncodedFragments() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of(
                "A/B~C 雪", Map.of("type", "string"),
                "Reference", Map.of("$ref", "#/components/schemas/A~1B~0C%20%E9%9B%AA"))));

        assertThat(prepared(input).path("components").path("schemas").has("Reference")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://hidden.example.com/schema", "file:///private/spec.yaml", "other.yaml#/Thing", "#anchor", "#/missing", "#/bad~2escape"})
    void rejectsNonSelfContainedOrInvalidReferences(String reference) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of("Node", Map.of("$ref", reference))));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("hidden.example.com").hasMessageNotContaining("/private/spec.yaml");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$id", "id", "$schema", "$dynamicRef", "$anchor", "$dynamicAnchor", "$recursiveRef"})
    void rejectsSchemaIdentifierAndDialectRebasing(String keyword) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of("Node", Map.of(keyword, "https://hidden.example.com/schema"))));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rebasing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "example", "value", "enum"})
    void componentAndResponseNamesCannotHideExternalReferences(String name) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("responses", Map.of(name, Map.of("$ref", "https://hidden.example.com/response"))));
        operation(input).put("responses", Map.of("default", Map.of("$ref", "#/components/responses/" + name)));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal");
    }

    @Test
    void defaultResponseItselfCannotHideAnExternalReference() {
        Map<String, Object> input = definition();
        operation(input).put("responses", Map.of("default", Map.of("$ref", "https://hidden.example.com/response")));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://hidden.example.com/schema", "other.yaml#/Thing", "file:///private/schema", "Unresolved"})
    void discriminatorMappingsCannotTriggerAnExternalResolution(String reference) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of("Animal", Map.of(
                "type", "object", "discriminator", Map.of("propertyName", "kind", "mapping", Map.of("dog", reference))))));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal").hasMessageNotContaining("hidden.example.com");
    }

    @Test
    void discriminatorMappingsMayReferenceExistingInternalSchemas() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("schemas", Map.of(
                "Animal", Map.of("type", "object", "discriminator", Map.of("propertyName", "kind", "mapping", Map.of("dog", "#/components/schemas/Dog"))),
                "Dog", Map.of("type", "object"))));
        assertThat(prepared(input).path("components").path("schemas").path("Animal").path("discriminator")
                .path("mapping").path("dog").asString()).isEqualTo("#/components/schemas/Dog");
    }

    @Test
    void rejectsCyclicResponseReferencesWhileKeepingSchemaCyclesSupported() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("responses", Map.of(
                "A", Map.of("$ref", "#/components/responses/B"),
                "B", Map.of("$ref", "#/components/responses/A"))));
        operation(input).put("responses", Map.of("200", Map.of("$ref", "#/components/responses/A")));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cyclic non-schema");
    }

    @Test
    void referencesIntoLiteralDataStillValidateTheReferencedSchema() {
        Map<String, Object> input = definition();
        info(input).put("example", Map.of("$ref", "https://hidden.example.com/schema"));
        input.put("components", Map.of("schemas", Map.of("Node", Map.of("$ref", "#/info/example"))));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal");
    }

    @Test
    void preservesDocumentationAndLiteralExampleUrlsWithoutTreatingThemAsFetches() {
        Map<String, Object> input = definition();
        input.put("externalDocs", Map.of("url", "https://docs.example.com/api"));
        operation(input).put("requestBody", Map.of("content", Map.of("application/json", Map.of(
                "schema", Map.of("type", "object", "properties", Map.of("server", Map.of("type", "string"))),
                "example", Map.of("server", "https://customer.example.com/", "url", "https://example.com/data")))));

        assertThat(prepared(input).toString()).contains("https://docs.example.com/api", "https://customer.example.com/", "https://example.com/data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"externalValue", "operationRef", "server"})
    void rejectsSemanticExternalExamplesAndLinkOverrides(String keyword) {
        Map<String, Object> input = definition();
        input.put("components", Map.of("examples", Map.of("Example", Map.of(keyword, "https://hidden.example.com/value"))));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void rejectsCallbacksAndWebhooksRatherThanClaimingTheirDestinationsAreConfined() {
        Map<String, Object> input = definition();
        operation(input).put("callbacks", Map.of("notify", Map.of("{$request.body#/url}", Map.of())));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("callbacks");
        operation(input).remove("callbacks");
        input.put("webhooks", Map.of("notify", Map.of("post", Map.of())));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("webhooks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "api.example.com/v1", "//api.example.com/v1", "ftp://api.example.com/v1",
            "https://user:secret@api.example.com/v1", "https://api.example.com/v1?token=secret", "https://api.example.com/v1#secret",
            "https://api.example.com:0/v1", "https://api.example.com/v1/../other", "https://api.example.com/v1/%2e%2e/other",
            "https://api.example.com/v1/%252e%252e/other", "https://api.example.com/{host}", "https://api.example.com/%7Bhost%7D"})
    void requiresAnExplicitSafeFullTarget(String target) {
        assertThatThrownBy(() -> policy.prepare(YAML, target)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
        verifyNoInteractions(urls);
    }

    @Test
    void blockedTargetsAndDerivedOperationUrlsDoNotEscapePolicyErrors() {
        doThrow(new IllegalArgumentException("blocked secret https://hidden.example.com")).when(urls).validateUrl(TARGET);
        assertThatThrownBy(() -> policy.prepare(YAML, TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret").hasMessageNotContaining("hidden.example.com");

        urls = mock(UrlValidationService.class);
        policy = new OpenApiContentPolicy(urls);
        doThrow(new IllegalArgumentException("blocked path")).when(urls).validateUrl(TARGET + "/pets");
        assertThatThrownBy(() -> policy.prepare(YAML, TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prohibited");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example.com/pets", "//other.example.com/pets", "/../pets", "/a/./pets", "/..;x/pets",
            "/%2e%2e/pets", "/%252e%252e/pets", "/a%2fb", "/a\\b", "/pets?next=other", "/pets#other",
            "/https://other.example.com/pets", "/{../path}", "/%7Bpath%7D"})
    void rejectsDangerousOperationPaths(String path) {
        Map<String, Object> input = definition();
        input.put("paths", Map.of(path, Map.of("get", operation(input))));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencedPathItemsAreValidatedAndTheirServersAreRewritten() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("pathItems", Map.of("Pets", pathItem(input))));
        input.put("paths", Map.of("/pets", new LinkedHashMap<>(Map.of("$ref", "#/components/pathItems/Pets"))));

        JsonNode result = prepared(input);

        assertThat(result.path("components").path("pathItems").path("Pets").path("get")
                .path("servers").get(0).path("url").asString()).isEqualTo(TARGET);
        verify(urls).validateUrl(TARGET + "/pets");
    }

    @Test
    void validatesReferencedPathParameterSamplesWithoutRejectingTheirDocumentation() {
        Map<String, Object> input = definition();
        input.put("components", Map.of("parameters", Map.of("PetId", Map.of(
                "name", "id", "in", "path", "required", true,
                "description", "See https://docs.example.com/api",
                "schema", Map.of("type", "string"),
                "examples", Map.of("ordinary", Map.of("summary", "See https://docs.example.com/api", "value", "pet-123"))))));
        Map<String, Object> item = pathItem(input);
        item.put("parameters", List.of(Map.of("$ref", "#/components/parameters/PetId")));
        input.put("paths", Map.of("/pets/{id}", item));

        assertThat(prepared(input).path("paths").has("/pets/{id}")).isTrue();
        verify(urls).validateUrl(TARGET + "/pets/openapi-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../private", "%2e%2e", "%252e%252e", "..;x", "https://other.example.com", "a\\b"})
    void rejectsPathParameterSamplesThatCouldChangeTheDerivedDestination(String sample) {
        Map<String, Object> input = definition();
        operation(input).put("parameters", List.of(Map.of("name", "id", "in", "path", "schema", Map.of("type", "string", "default", sample))));
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsContentValuedPathParametersBeforeZapsMediaExampleExpansion() {
        Map<String, Object> input = definition();
        Map<String, Object> item = pathItem(input);
        operation(input).put("parameters", List.of(Map.of(
                "name", "id", "in", "path", "required", true,
                "content", Map.of("application/json", Map.of("example", "../../admin")))));
        input.put("paths", Map.of("/pets/{id}", item));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content-valued");
    }

    @ParameterizedTest
    @ValueSource(strings = {"properties", "additionalProperties", "patternProperties"})
    void rejectsImplicitObjectPathSchemasBeforeZapsArrayItemExpansion(String objectKeyword) {
        Map<String, Object> input = definition();
        Map<String, Object> property = Map.of("type", "string", "example", "../../../../admin");
        Map<String, Object> implicitObject = Map.of(objectKeyword,
                "additionalProperties".equals(objectKeyword) ? property : Map.of("x", property));
        operation(input).put("parameters", List.of(Map.of(
                "name", "id", "in", "path", "required", true,
                "schema", Map.of("type", "array", "items", implicitObject))));
        input.put("paths", Map.of("/pets/{id}", pathItem(input)));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Object-valued");
    }

    @Test
    void rejectsObjectInPathSchemaTypeUnion() {
        Map<String, Object> input = definition();
        operation(input).put("parameters", List.of(Map.of(
                "name", "id", "in", "path", "schema", Map.of("type", List.of("string", "object")))));

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Object-valued");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.2.0", "3.1", "3.0.0-rc1", "2.0", "1.2", "not-a-version"})
    void rejectsUnsupportedOrAmbiguousVersions(String version) {
        Map<String, Object> input = definition();
        input.put("openapi", version);
        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("versions");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openapi: 3.0.3\nopenapi: 3.1.0\n", "{\"openapi\":\"3.0.3\",\"openapi\":\"3.1.0\"}",
            "{\"openapi\":\"3.0.3\",\"\\u006fpenapi\":\"3.1.0\"}", "!!java/object:java.lang.ProcessBuilder {}",
            "!!set {foo: null}", "? [one, two]\n: value", "value: &data [one]\nother: *data",
            "value: &data one\nother: *data", "value: .nan", "value: .inf", "value: 2026-10-04", "[one, two]", "{broken"})
    void rejectsDuplicatesUnsafeYamlAliasesAndMalformedDocumentsWithoutEchoingInput(String content) {
        assertThatThrownBy(() -> policy.prepare(content, TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("ProcessBuilder").hasMessageNotContaining("&data");
    }

    @Test
    void boundsUtf8BytesBeforeParsingOrCallingUrlPolicy() {
        String content = "雪".repeat(OpenApiContentPolicy.MAX_INPUT_BYTES / 3 + 1);
        assertThatThrownBy(() -> policy.prepare(content, TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 input limit");
        verifyNoInteractions(urls);
    }

    @Test
    void rejectsMalformedUnicodeBeforeEncoding() {
        assertThatThrownBy(() -> policy.prepare("\ud800", TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unicode");
        verifyNoInteractions(urls);
    }

    @Test
    void boundsNestingAndNodesBeforeConstruction() {
        Map<String, Object> deepInput = definition();
        Object deep = "leaf";
        for (int index = 0; index < OpenApiContentPolicy.MAX_DEPTH + 1; index++) {
            deep = Map.of("child", deep);
        }
        deepInput.put("x-depth", deep);
        assertThatThrownBy(() -> prepared(deepInput)).isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> input = definition();
        input.put("x-nodes", java.util.Collections.nCopies(OpenApiContentPolicy.MAX_NODES, "leaf"));
        Map<String, Object> oversized = input;
        assertThatThrownBy(() -> prepared(oversized)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("node");
    }

    @Test
    void boundsNumericParsingBeforeConstructingLargeNumbers() {
        assertThatThrownBy(() -> policy.prepare("value: " + "1".repeat(1_001), TARGET))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("numeric");
    }

    @Test
    void boundsOperationCountIndependentlyOfTheDocumentByteLimit() {
        Map<String, Object> input = definition();
        Map<String, Object> paths = new LinkedHashMap<>();
        for (int index = 0; index < OpenApiContentPolicy.MAX_OPERATIONS + 1; index++) {
            paths.put("/op-" + index, Map.of("get", new LinkedHashMap<>()));
        }
        input.put("paths", paths);

        assertThatThrownBy(() -> prepared(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operation limit");
    }

    @Test
    void boundsEncodedOutputEvenWhenYamlInputIsSmaller() {
        // YAML's literal tab values become two-byte JSON escapes.
        String text = "a\t".repeat(350_000);
        String content = YAML + "description: |\n  " + text + "\n";
        assertThat(content.getBytes(StandardCharsets.UTF_8).length).isLessThan(OpenApiContentPolicy.MAX_INPUT_BYTES);

        assertThatThrownBy(() -> policy.prepare(content, TARGET)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output limit");
    }

    private JsonNode prepared(Map<String, Object> input) {
        return JSON.readTree(policy.prepare(JSON.writeValueAsString(input), TARGET));
    }

    private static Map<String, Object> definition() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openapi", "3.0.3");
        result.put("info", new LinkedHashMap<>(Map.of("title", "Example", "version", "1")));
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("responses", Map.of("200", Map.of("description", "Okay")));
        result.put("paths", new LinkedHashMap<>(Map.of("/pets", new LinkedHashMap<>(Map.of("get", operation)))));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> info(Map<String, Object> input) {
        return (Map<String, Object>) input.get("info");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pathItem(Map<String, Object> input) {
        return (Map<String, Object>) ((Map<String, Object>) input.get("paths")).get("/pets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(Map<String, Object> input) {
        return (Map<String, Object>) pathItem(input).get("get");
    }
}
