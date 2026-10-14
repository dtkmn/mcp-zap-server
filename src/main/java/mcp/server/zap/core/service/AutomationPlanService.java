package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineAutomationAccess;
import mcp.server.zap.core.gateway.EngineAutomationAccess.AutomationPlanProgress;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.ScannerInventory;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
public class AutomationPlanService {
    private static final int DEFAULT_STATUS_MESSAGE_LIMIT = 20;
    private static final int MAX_STATUS_MESSAGE_LIMIT = 100;
    private static final int DEFAULT_ARTIFACT_LIMIT = 20;
    private static final int MAX_ARTIFACT_LIMIT = 50;
    private static final int DEFAULT_ARTIFACT_PREVIEW_CHARS = 8000;
    private static final int MAX_ARTIFACT_PREVIEW_CHARS = 50000;
    private static final int MAX_PREVIEW_PER_FILE = 2000;
    private static final int MAX_PLAN_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_PLAN_CODE_POINTS = 1024 * 1024;
    private static final int MAX_PLAN_EXPANDED_NODES = 10_000;
    private static final int MAX_PLAN_EXPANDED_DEPTH = 50;
    private static final int MAX_PLAN_EXPANDED_SCALAR_CHARS = 1024 * 1024;
    private static final int MAX_PLAN_COLLECTION_ALIASES = 50;

    private final EngineAutomationAccess automationAccess;
    private final AutomationPlanTargetPolicy targetPolicy;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private EngineRuntimeAccess runtimeAccess;

    @Value("${zap.automation.local-directory:/zap/wrk/automation}")
    private String automationLocalDirectory;

    @Value("${zap.automation.zap-directory:/zap/wrk/automation}")
    private String automationZapDirectory;

    public AutomationPlanService(EngineAutomationAccess automationAccess, UrlValidationService urlValidationService) {
        this.automationAccess = automationAccess;
        this.targetPolicy = new AutomationPlanTargetPolicy(urlValidationService);
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setRuntimeAccess(EngineRuntimeAccess runtimeAccess) {
        this.runtimeAccess = runtimeAccess;
    }

    public String runAutomationPlan(
            String planPath,
            String planYaml,
            String planFileName
    ) {
        PreparedAutomationPlan preparedPlan = preparePlan(planPath, planYaml, planFileName);

        String returnedPlanId = automationAccess.runAutomationPlan(preparedPlan.zapPlanPath().toString());
        trackAutomationPlanStarted(returnedPlanId, resolveWorkspaceId());
        return formatRunResponse(returnedPlanId, preparedPlan);
    }

    public String getAutomationPlanStatus(
            String planId,
            Integer maxMessages
    ) {
        String normalizedPlanId = requireText(planId, "planId");
        int boundedMaxMessages = validateBoundedPositive(maxMessages, DEFAULT_STATUS_MESSAGE_LIMIT, MAX_STATUS_MESSAGE_LIMIT, "maxMessages");

        AutomationPlanProgress progress = automationAccess.loadAutomationPlanProgress(normalizedPlanId);
        boolean completed = progress.completed();
        trackAutomationPlanStatus(normalizedPlanId, completed);

        StringBuilder output = new StringBuilder()
                .append("Automation plan status:\n")
                .append("Plan ID: ").append(normalizedPlanId).append('\n')
                .append("Started: ").append(progress.started()).append('\n')
                .append("Finished: ").append(completed ? progress.finished() : "<running>").append('\n')
                .append("Completed: ").append(completed ? "yes" : "no").append('\n')
                .append("Successful: ").append(planSuccess(progress)).append('\n')
                .append("Info Messages: ").append(progress.info().size()).append('\n')
                .append("Warnings: ").append(progress.warnings().size()).append('\n')
                .append("Errors: ").append(progress.errors().size()).append('\n');

        appendMessages(output, "Info", progress.info(), boundedMaxMessages);
        appendMessages(output, "Warnings", progress.warnings(), boundedMaxMessages);
        appendMessages(output, "Errors", progress.errors(), boundedMaxMessages);

        if (completed && progress.errors().isEmpty()) {
            output.append("ZAP reports that the plan finished, but its progress API does not expose a success or termination reason. "
                    + "Review job messages and artifacts; completion alone does not establish that every job ran.\n");
        }

        if (completed) {
            output.append("Plan completion does not establish that all scanner requests stopped or the passive backlog drained. "
                    + "Check scanner status separately before treating reports as final.\n");
        }

        if (!completed) {
            output.append("Use 'zap_automation_plan_artifacts' with the returned plan file path to inspect generated files while the plan is running.");
        }
        return output.toString();
    }

    public String getAutomationPlanArtifacts(
            String planPath,
            Integer maxArtifacts,
            Integer maxChars
    ) {
        Path localRoot = localAutomationRoot();
        Path normalizedPlanPath = resolveWithinRoot(localRoot, requireText(planPath, "planPath"), "planPath");
        if (!Files.exists(normalizedPlanPath) || !Files.isRegularFile(normalizedPlanPath)) {
            throw new IllegalArgumentException("Plan file does not exist: " + normalizedPlanPath);
        }

        int boundedArtifactLimit = validateBoundedPositive(maxArtifacts, DEFAULT_ARTIFACT_LIMIT, MAX_ARTIFACT_LIMIT, "maxArtifacts");
        int boundedPreviewChars = validateBoundedPositive(maxChars, DEFAULT_ARTIFACT_PREVIEW_CHARS, MAX_ARTIFACT_PREVIEW_CHARS, "maxChars");

        AutomationPlanDescriptor descriptor = parsePlanDescriptor(readFile(normalizedPlanPath), normalizedPlanPath);
        Path runDirectory = normalizedPlanPath.getParent();

        StringBuilder output = new StringBuilder()
                .append("Automation plan artifacts:\n")
                .append("Plan File: ").append(normalizedPlanPath).append('\n')
                .append("Run Directory: ").append(runDirectory).append('\n')
                .append("Declared Report Jobs: ").append(descriptor.reportArtifacts().size()).append('\n');

        int remainingPreviewChars = boundedPreviewChars;
        int renderedArtifacts = 0;

        renderedArtifacts += appendArtifactEntry(output, "plan", normalizedPlanPath, null, null, remainingPreviewChars);
        remainingPreviewChars = reducePreviewBudget(remainingPreviewChars, normalizedPlanPath);

        if (descriptor.reportArtifacts().isEmpty()) {
            output.append("No report jobs were declared in this plan. Use 'zap_automation_plan_status' for progress and result messages.\n");
            return output.toString();
        }

        for (ReportArtifactSpec reportArtifact : descriptor.reportArtifacts()) {
            if (renderedArtifacts >= boundedArtifactLimit) {
                output.append("Artifact output truncated at ").append(boundedArtifactLimit).append(" entries.\n");
                break;
            }

            List<Path> discoveredFiles = discoverArtifactFiles(reportArtifact);
            if (discoveredFiles.isEmpty()) {
                output.append("Report Job ").append(reportArtifact.jobIndex()).append(": no generated files found yet\n")
                        .append("Directory: ").append(reportArtifact.localDirectory()).append('\n');
                if (reportArtifact.reportFile() != null) {
                    output.append("Expected File Prefix: ").append(reportArtifact.reportFile()).append('\n');
                }
                continue;
            }

            for (Path artifactPath : discoveredFiles) {
                if (renderedArtifacts >= boundedArtifactLimit) {
                    output.append("Artifact output truncated at ").append(boundedArtifactLimit).append(" entries.\n");
                    break;
                }
                output.append("Report Job ").append(reportArtifact.jobIndex()).append(": ")
                        .append(defaultDisplayValue(reportArtifact.template())).append('\n');
                renderedArtifacts += appendArtifactEntry(
                        output,
                        "report",
                        artifactPath,
                        reportArtifact.reportFile(),
                        reportArtifact.template(),
                        remainingPreviewChars
                );
                remainingPreviewChars = reducePreviewBudget(remainingPreviewChars, artifactPath);
            }
        }

        if (remainingPreviewChars <= 0) {
            output.append("Preview budget exhausted. Use 'zap_report_read' on report artifacts for deeper inspection.\n");
        }
        return output.toString();
    }

    private PreparedAutomationPlan preparePlan(String planPath, String planYaml, String planFileName) {
        boolean hasPlanPath = hasText(planPath);
        validatePlanInputSize(planYaml);
        boolean hasPlanYaml = hasText(planYaml);
        if (hasPlanPath == hasPlanYaml) {
            throw new IllegalArgumentException("Provide exactly one of planPath or planYaml");
        }

        Path localRoot = localAutomationRoot();
        Path zapRoot = zapAutomationRoot();
        createDirectories(localRoot.resolve("runs"));

        String sourcePlanName;
        String sourcePlanContent;
        if (hasPlanYaml) {
            sourcePlanName = normalizePlanFileName(planFileName);
            sourcePlanContent = planYaml;
        } else {
            Path sourcePlanPath = resolveWithinRoot(localRoot, planPath.trim(), "planPath");
            if (!Files.exists(sourcePlanPath) || !Files.isRegularFile(sourcePlanPath)) {
                throw new IllegalArgumentException("Plan file does not exist: " + sourcePlanPath);
            }
            sourcePlanName = normalizePlanFileName(sourcePlanPath.getFileName().toString());
            sourcePlanContent = readFile(sourcePlanPath);
        }

        Map<String, Object> normalizedPlan = parseYamlObject(sourcePlanContent);
        targetPolicy.validate(normalizedPlan);
        validatePlanCapabilities(normalizedPlan);

        String runId = "plan-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path localRunDirectory = localRoot.resolve("runs").resolve(runId).normalize();
        Path localArtifactsDirectory = localRunDirectory.resolve("artifacts").normalize();
        Path zapRunDirectory = zapRoot.resolve("runs").resolve(runId).normalize();
        Path zapArtifactsDirectory = zapRunDirectory.resolve("artifacts").normalize();

        createDirectories(localArtifactsDirectory);

        List<ReportArtifactSpec> reportArtifacts = normalizeReportJobs(
                normalizedPlan,
                localRoot,
                zapRoot,
                zapRunDirectory,
                zapArtifactsDirectory
        );

        Path localPlanPath = localRunDirectory.resolve(sourcePlanName).normalize();
        Path zapPlanPath = zapRunDirectory.resolve(sourcePlanName).normalize();

        createDirectories(localPlanPath.getParent());
        writeFile(localPlanPath, dumpYaml(normalizedPlan));

        return new PreparedAutomationPlan(localPlanPath, zapPlanPath, localRunDirectory, localArtifactsDirectory, reportArtifacts);
    }

    private void validatePlanCapabilities(Map<String, Object> plan) {
        Map<String, Object> parameters = childMap(childMap(plan.get("env")).get("parameters"));
        if (!parameters.containsKey("maxDuration")) {
            return;
        }
        ScannerInventory inventory;
        try {
            inventory = runtimeAccess == null ? null : runtimeAccess.readScannerInventory();
        } catch (RuntimeException _) {
            throw unsupportedWholePlanDuration();
        }
        if (inventory == null || !inventory.inventoryComplete()
                || !versionAtLeast(inventory.zapVersion(), 2, 17, 0)) {
            throw unsupportedWholePlanDuration();
        }
        String automationVersion = inventory.addOns().stream()
                .filter(addOn -> "automation".equals(addOn.id()))
                .map(EngineRuntimeAccess.AddOn::version)
                .findFirst().orElse(null);
        if (!versionAtLeast(automationVersion, 0, 61, 0)) {
            throw unsupportedWholePlanDuration();
        }
    }

    private static boolean versionAtLeast(String version, int major, int minor, int patch) {
        if (version == null || !version.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")) {
            return false;
        }
        String[] parts = version.split("\\.");
        int[] minimum = {major, minor, patch};
        for (int i = 0; i < minimum.length; i++) {
            int comparison = Integer.compare(Integer.parseInt(parts[i]), minimum[i]);
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return true;
    }

    private static IllegalArgumentException unsupportedWholePlanDuration() {
        return new IllegalArgumentException("Automation plan env.parameters.maxDuration requires a verified ZAP 2.17.0 or newer "
                + "and automation add-on 0.61.0 or newer. Check engine connectivity and install a compatible stable add-on. "
                + "Omit this field only if an unlimited whole-plan duration is acceptable.");
    }

    private String planSuccess(AutomationPlanProgress progress) {
        if (!progress.completed()) {
            return "pending";
        }
        if (!progress.errors().isEmpty()) {
            return "no";
        }
        return "unknown";
    }

    private void trackAutomationPlanStarted(String planId, String workspaceId) {
        if (operationRegistry == null || !hasText(planId)) {
            return;
        }
        operationRegistry.registerAutomationPlan(planId, workspaceId);
    }

    private void trackAutomationPlanStatus(String planId, boolean completed) {
        if (operationRegistry == null || !hasText(planId)) {
            return;
        }
        if (completed) {
            operationRegistry.releaseAutomationPlan(planId);
            return;
        }
        operationRegistry.touchAutomationPlan(planId);
    }

    private String resolveWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        return clientWorkspaceResolver.resolveCurrentWorkspaceId();
    }

    private List<ReportArtifactSpec> normalizeReportJobs(Map<String, Object> plan,
                                                         Path localRoot,
                                                         Path zapRoot,
                                                         Path zapRunDirectory,
                                                         Path zapArtifactsDirectory) {
        List<Map<String, Object>> jobs = childMapList(plan.get("jobs"));
        List<ReportArtifactSpec> reportArtifacts = new ArrayList<>();

        for (int i = 0; i < jobs.size(); i++) {
            Map<String, Object> job = jobs.get(i);
            String type = trimToNull(stringValue(job.get("type")));
            if (!"report".equalsIgnoreCase(type)) {
                continue;
            }

            Map<String, Object> parameters = childMap(job.computeIfAbsent("parameters", ignored -> new LinkedHashMap<>()));
            String reportDirValue = trimToNull(stringValue(parameters.get("reportDir")));
            Path zapReportDirectory;
            if (reportDirValue == null) {
                zapReportDirectory = zapArtifactsDirectory;
            } else {
                zapReportDirectory = resolveZapScopedPath(zapRoot, zapRunDirectory, reportDirValue, "reportDir");
            }

            Path localReportDirectory = mapZapPathToLocalPath(zapRoot, localRoot, zapReportDirectory);
            createDirectories(localReportDirectory);

            parameters.put("reportDir", zapReportDirectory.toString());
            String reportFile = trimToNull(stringValue(parameters.get("reportFile")));
            String template = trimToNull(stringValue(parameters.get("template")));
            reportArtifacts.add(new ReportArtifactSpec(i + 1, template, reportFile, localReportDirectory));
        }

        return List.copyOf(reportArtifacts);
    }

    private Map<String, Object> parseYamlObject(String yamlText) {
        validatePlanInputSize(yamlText);
        if (!hasText(yamlText)) {
            throw new IllegalArgumentException("Automation plan content cannot be blank");
        }

        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(MAX_PLAN_CODE_POINTS);
        options.setNestingDepthLimit(MAX_PLAN_EXPANDED_DEPTH);
        options.setMaxAliasesForCollections(MAX_PLAN_COLLECTION_ALIASES);
        options.setAllowRecursiveKeys(false);
        options.setMergeOnCompose(false);
        PlanSafeConstructor constructor = new PlanSafeConstructor(options);
        Object loaded;
        try {
            Node root = new Yaml(constructor).compose(new StringReader(yamlText));
            if (root == null) {
                throw new IllegalArgumentException("Automation plan YAML must be a mapping at the root");
            }
            // Compose retains alias identities without hashing keys or flattening merges. Bound
            // their full expansion before SafeConstructor or normalization can do that work.
            new PlanNodeBudget().visit(root, 1);
            loaded = constructor.constructPlan(root);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Invalid automation plan YAML: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Automation plan YAML must be a mapping at the root");
        }
        return normalizeMap(rawMap);
    }

    private void validatePlanInputSize(String yamlText) {
        if (yamlText == null) {
            return;
        }
        if (yamlText.length() > MAX_PLAN_INPUT_BYTES) {
            throw planInputTooLarge();
        }
        int bytes = 0;
        for (int i = 0; i < yamlText.length(); i++) {
            char character = yamlText.charAt(i);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character) && i + 1 < yamlText.length()
                    && Character.isLowSurrogate(yamlText.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                // UTF-8 replaces an unpaired surrogate with one byte; SnakeYAML rejects it.
                bytes += Character.isSurrogate(character) ? 1 : 3;
            }
            if (bytes > MAX_PLAN_INPUT_BYTES) {
                throw planInputTooLarge();
            }
        }
    }

    private static IllegalArgumentException planInputTooLarge() {
        return new IllegalArgumentException("Automation plan YAML exceeds " + MAX_PLAN_INPUT_BYTES + " UTF-8 bytes");
    }

    private static final class PlanSafeConstructor extends SafeConstructor {
        private PlanSafeConstructor(LoaderOptions options) {
            super(options);
        }

        private Object constructPlan(Node root) {
            return constructDocument(root);
        }
    }

    private static final class PlanNodeBudget {
        private final Set<Node> activeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        private int expandedNodes;
        private long expandedScalarChars;

        private void visit(Node node, int depth) {
            if (!activeNodes.add(node)) {
                throw new IllegalArgumentException("Automation plan YAML contains cyclic aliases");
            }
            try {
                if (++expandedNodes > MAX_PLAN_EXPANDED_NODES) {
                    throw new IllegalArgumentException("Automation plan YAML exceeds " + MAX_PLAN_EXPANDED_NODES + " expanded nodes");
                }
                if (depth > MAX_PLAN_EXPANDED_DEPTH) {
                    throw new IllegalArgumentException("Automation plan YAML exceeds expanded depth " + MAX_PLAN_EXPANDED_DEPTH);
                }
                if (node instanceof ScalarNode scalar) {
                    expandedScalarChars += scalar.getValue().length();
                    if (expandedScalarChars > MAX_PLAN_EXPANDED_SCALAR_CHARS) {
                        throw new IllegalArgumentException("Automation plan YAML exceeds " + MAX_PLAN_EXPANDED_SCALAR_CHARS + " expanded scalar characters");
                    }
                } else if (node instanceof SequenceNode sequence) {
                    for (Node child : sequence.getValue()) {
                        visit(child, depth + 1);
                    }
                } else if (node instanceof MappingNode mapping) {
                    for (NodeTuple tuple : mapping.getValue()) {
                        if (!(tuple.getKeyNode() instanceof ScalarNode)) {
                            throw new IllegalArgumentException("Automation plan YAML requires scalar mapping keys");
                        }
                        visit(tuple.getKeyNode(), depth + 1);
                        visit(tuple.getValueNode(), depth + 1);
                    }
                }
            } finally {
                activeNodes.remove(node);
            }
        }
    }

    private String dumpYaml(Map<String, Object> plan) {
        DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        PlanOutputStream output = new PlanOutputStream();
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            new Yaml(options).dump(plan, writer);
        } catch (IOException e) {
            throw new ZapApiException("Error normalizing automation plan YAML", e);
        }
        String yamlText = output.toString(StandardCharsets.UTF_8);
        // Normalization can add report parameters or expand aliases. The returned plan file
        // must satisfy the same parser limits used when artifacts are inspected later.
        parseYamlObject(yamlText);
        return yamlText;
    }

    private static final class PlanOutputStream extends ByteArrayOutputStream {
        @Override
        public synchronized void write(int value) {
            if (count >= MAX_PLAN_INPUT_BYTES) {
                throw planInputTooLarge();
            }
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > MAX_PLAN_INPUT_BYTES - count) {
                throw planInputTooLarge();
            }
            super.write(bytes, offset, length);
        }
    }

    private Map<String, Object> normalizeMap(Map<?, ?> rawMap) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), normalizeValue(entry.getValue()));
        }
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return normalizeMap(mapValue);
        }
        if (value instanceof List<?> listValue) {
            List<Object> normalized = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                normalized.add(normalizeValue(item));
            }
            return normalized;
        }
        return value;
    }

    private Path resolveZapScopedPath(Path zapRoot, Path zapRunDirectory, String rawPath, String fieldName) {
        Path candidate = Paths.get(rawPath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : zapRunDirectory.resolve(candidate).normalize();
        if (!resolved.startsWith(zapRoot)) {
            throw new IllegalArgumentException(fieldName + " must stay within the automation workspace visible to ZAP");
        }
        return resolved;
    }

    private Path mapZapPathToLocalPath(Path zapRoot, Path localRoot, Path zapPath) {
        if (!zapPath.startsWith(zapRoot)) {
            throw new IllegalArgumentException("Path must stay within the automation workspace visible to ZAP");
        }
        Path relative = zapRoot.relativize(zapPath);
        Path localPath = localRoot.resolve(relative).normalize();
        if (!localPath.startsWith(localRoot)) {
            throw new IllegalArgumentException("Resolved local path escapes the automation workspace");
        }
        return localPath;
    }

    private Path resolveWithinRoot(Path root, String pathValue, String fieldName) {
        Path candidate = Paths.get(pathValue);
        Path resolved = candidate.isAbsolute() ? candidate.toAbsolutePath().normalize() : root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(fieldName + " must stay within the configured automation workspace");
        }
        return resolved;
    }

    private Path validateWorkspacePath(Path path) throws IOException {
        Path root = Paths.get(requireText(automationLocalDirectory, "automationLocalDirectory"))
                .toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Automation paths must stay within the configured workspace without symbolic links");
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Automation paths must not contain symbolic links");
            }
        }
        // Check the actual location as well, including filesystem redirects that are not
        // reported as symbolic links. Missing ordinary output paths remain valid.
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                && !normalized.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("Automation paths must stay within the configured workspace");
        }
        return normalized;
    }

    private BasicFileAttributes validateWorkspaceFile(Path path) throws IOException {
        Path normalized = validateWorkspacePath(path);
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("Automation artifacts must be regular files");
        }
        try {
            if (((Number) Files.getAttribute(normalized, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw new IllegalArgumentException("Automation files must not have multiple hard links");
            }
        } catch (UnsupportedOperationException _) {
            // Other filesystems still enforce containment, symbolic-link and regular-file checks.
        }
        return attributes;
    }

    private Path localAutomationRoot() {
        Path root = Paths.get(requireText(automationLocalDirectory, "automationLocalDirectory")).toAbsolutePath().normalize();
        createDirectories(root);
        return root;
    }

    private Path zapAutomationRoot() {
        return Paths.get(requireText(automationZapDirectory, "automationZapDirectory")).normalize();
    }

    private String formatRunResponse(String planId, PreparedAutomationPlan preparedPlan) {
        StringBuilder output = new StringBuilder()
                .append("Automation plan started.\n")
                .append("Plan ID: ").append(planId).append('\n')
                .append("Plan File: ").append(preparedPlan.localPlanPath()).append('\n')
                .append("Plan Workspace: ").append(preparedPlan.localRunDirectory()).append('\n')
                .append("Artifacts Directory: ").append(preparedPlan.localArtifactsDirectory()).append('\n');

        if (preparedPlan.reportArtifacts().isEmpty()) {
            output.append("Declared Report Jobs: 0\n");
        } else {
            output.append("Declared Report Jobs: ").append(preparedPlan.reportArtifacts().size()).append('\n');
            for (ReportArtifactSpec reportArtifact : preparedPlan.reportArtifacts()) {
                output.append("- Report Job ").append(reportArtifact.jobIndex())
                        .append(": dir=").append(reportArtifact.localDirectory());
                if (reportArtifact.reportFile() != null) {
                    output.append(" prefix=").append(reportArtifact.reportFile());
                }
                if (reportArtifact.template() != null) {
                    output.append(" template=").append(reportArtifact.template());
                }
                output.append('\n');
            }
        }

        output.append("Use 'zap_automation_plan_status' with the returned plan ID and 'zap_automation_plan_artifacts' with the returned plan file path.");
        return output.toString();
    }

    private void appendMessages(StringBuilder output, String label, List<String> messages, int maxMessages) {
        if (messages.isEmpty()) {
            return;
        }

        int shown = Math.min(messages.size(), maxMessages);
        output.append(label).append(":\n");
        for (int i = 0; i < shown; i++) {
            output.append("- ").append(messages.get(i)).append('\n');
        }
        if (messages.size() > shown) {
            output.append("- ... ").append(messages.size() - shown).append(" more\n");
        }
    }

    private int appendArtifactEntry(StringBuilder output,
                                    String type,
                                    Path artifactPath,
                                    String reportFilePrefix,
                                    String template,
                                    int remainingPreviewChars) {
        try {
            artifactPath = validateWorkspacePath(artifactPath);
            output.append("Artifact Type: ").append(type).append('\n')
                    .append("Path: ").append(artifactPath).append('\n')
                    .append("Exists: ").append(Files.exists(artifactPath, LinkOption.NOFOLLOW_LINKS) ? "yes" : "no").append('\n');
            if (!Files.exists(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
                return 1;
            }
            BasicFileAttributes attributes = validateWorkspaceFile(artifactPath);
            output.append("Size: ").append(attributes.size()).append(" bytes\n");
            if (reportFilePrefix != null) {
                output.append("Declared Prefix: ").append(reportFilePrefix).append('\n');
            }
            if (template != null) {
                output.append("Template: ").append(template).append('\n');
            }
            if (remainingPreviewChars > 0 && isPreviewableTextFile(artifactPath)) {
                int previewChars = Math.min(remainingPreviewChars, MAX_PREVIEW_PER_FILE);
                char[] preview = new char[previewChars + 1];
                int returned;
                try (InputStream input = Files.newInputStream(artifactPath, LinkOption.NOFOLLOW_LINKS);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                             StandardCharsets.UTF_8.newDecoder()))) {
                    returned = 0;
                    int count;
                    while (returned < preview.length
                            && (count = reader.read(preview, returned, preview.length - returned)) != -1) {
                        returned += count;
                    }
                }
                validateWorkspaceFile(artifactPath);
                boolean truncated = returned > previewChars;
                String body = new String(preview, 0, Math.min(returned, previewChars));
                output.append("Preview (truncated=").append(truncated ? "yes" : "no").append("):\n")
                        .append(body).append('\n');
            }
        } catch (IOException e) {
            log.warn("Unable to inspect automation artifact {}: {}", artifactPath, e.getMessage());
            output.append("Inspection Error: ").append(e.getMessage()).append('\n');
        }
        return 1;
    }

    private int reducePreviewBudget(int remainingPreviewChars, Path artifactPath) {
        if (remainingPreviewChars <= 0 || !Files.exists(artifactPath) || !isPreviewableTextFile(artifactPath)) {
            return remainingPreviewChars;
        }
        return Math.max(0, remainingPreviewChars - MAX_PREVIEW_PER_FILE);
    }

    private List<Path> discoverArtifactFiles(ReportArtifactSpec reportArtifact) {
        try {
            Path directory = validateWorkspacePath(reportArtifact.localDirectory());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (Stream<Path> children = Files.list(directory)) {
                String prefix = trimToNull(reportArtifact.reportFile());
                List<Path> entries = children
                        .filter(path -> prefix == null || matchesReportFile(path.getFileName().toString(), prefix))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
                for (Path entry : entries) {
                    validateWorkspacePath(entry);
                }
                return entries.stream()
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .toList();
            }
        } catch (IOException e) {
            log.warn("Unable to inspect automation artifact directory {}: {}", reportArtifact.localDirectory(), e.getMessage());
            return List.of();
        }
    }

    private boolean matchesReportFile(String fileName, String declaredReportFile) {
        return Objects.equals(fileName, declaredReportFile)
                || fileName.startsWith(declaredReportFile + ".")
                || fileName.startsWith(declaredReportFile + "-");
    }

    private boolean isPreviewableTextFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".json")
                || fileName.endsWith(".txt")
                || fileName.endsWith(".yaml")
                || fileName.endsWith(".yml")
                || fileName.endsWith(".md")
                || fileName.endsWith(".xml")
                || fileName.endsWith(".html")
                || fileName.endsWith(".csv");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Object value) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> childMapList(Object value) {
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : listValue) {
            if (item instanceof Map<?, ?>) {
                results.add((Map<String, Object>) item);
            }
        }
        return results;
    }

    private String normalizePlanFileName(String planFileName) {
        String effectiveName = trimToNull(planFileName);
        if (effectiveName == null) {
            return "automation-plan.yaml";
        }

        String sanitized = effectiveName.replaceAll("[^A-Za-z0-9._-]", "-");
        if (sanitized.isBlank()) {
            sanitized = "automation-plan";
        }
        if (!(sanitized.endsWith(".yaml") || sanitized.endsWith(".yml"))) {
            sanitized = sanitized + ".yaml";
        }
        return sanitized;
    }

    private int validateBoundedPositive(Integer value, int defaultValue, int maxValue, String fieldName) {
        int effectiveValue = value == null ? defaultValue : value;
        if (effectiveValue <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return Math.min(effectiveValue, maxValue);
    }

    private String readFile(Path path) {
        try {
            BasicFileAttributes attributes = validateWorkspaceFile(path);
            if (attributes.size() > MAX_PLAN_INPUT_BYTES) {
                throw planInputTooLarge();
            }
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                // Bound the read itself as well: a workspace file can grow after the size check.
                byte[] content = input.readNBytes(MAX_PLAN_INPUT_BYTES + 1);
                if (content.length > MAX_PLAN_INPUT_BYTES) {
                    throw planInputTooLarge();
                }
                validateWorkspaceFile(path);
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            }
        } catch (IOException e) {
            log.error("Error reading automation plan {}: {}", path, e.getMessage(), e);
            throw new ZapApiException("Error reading automation plan file", e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            path = validateWorkspacePath(path);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            log.error("Error writing automation plan {}: {}", path, e.getMessage(), e);
            throw new ZapApiException("Error writing automation plan file", e);
        }
    }

    private void createDirectories(Path path) {
        try {
            path = validateWorkspacePath(path);
            Files.createDirectories(path);
            validateWorkspacePath(path);
        } catch (IOException e) {
            log.error("Error creating automation directory {}: {}", path, e.getMessage(), e);
            throw new ZapApiException("Error preparing automation workspace", e);
        }
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String defaultDisplayValue(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private record PreparedAutomationPlan(
            Path localPlanPath,
            Path zapPlanPath,
            Path localRunDirectory,
            Path localArtifactsDirectory,
            List<ReportArtifactSpec> reportArtifacts
    ) {
    }

    private record AutomationPlanDescriptor(
            List<ReportArtifactSpec> reportArtifacts
    ) {
    }

    private record ReportArtifactSpec(
            int jobIndex,
            String template,
            String reportFile,
            Path localDirectory
    ) {
    }

    private AutomationPlanDescriptor parsePlanDescriptor(String yamlText, Path normalizedPlanPath) {
        Map<String, Object> plan = parseYamlObject(yamlText);
        Path localRoot = localAutomationRoot();
        Path zapRoot = zapAutomationRoot();
        List<ReportArtifactSpec> reportArtifacts = new ArrayList<>();
        List<Map<String, Object>> jobs = childMapList(plan.get("jobs"));
        for (int i = 0; i < jobs.size(); i++) {
            Map<String, Object> job = jobs.get(i);
            String type = trimToNull(stringValue(job.get("type")));
            if (!"report".equalsIgnoreCase(type)) {
                continue;
            }
            Map<String, Object> parameters = childMap(job.get("parameters"));
            targetPolicy.validateReportParameters(parameters, "jobs[" + i + "].parameters");
            String reportDirValue = trimToNull(stringValue(parameters.get("reportDir")));
            Path localDirectory;
            if (reportDirValue == null) {
                localDirectory = normalizedPlanPath.getParent().resolve("artifacts").normalize();
            } else {
                localDirectory = mapZapPathToLocalPath(zapRoot, localRoot, resolveZapScopedPath(zapRoot, zapRoot, reportDirValue, "reportDir"));
            }
            reportArtifacts.add(new ReportArtifactSpec(
                    i + 1,
                    trimToNull(stringValue(parameters.get("template"))),
                    trimToNull(stringValue(parameters.get("reportFile"))),
                    localDirectory
            ));
        }
        return new AutomationPlanDescriptor(List.copyOf(reportArtifacts));
    }
}
