package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineAutomationAccess;
import mcp.server.zap.core.gateway.EngineAutomationAccess.AutomationPlanProgress;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    private final EngineAutomationAccess automationAccess;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;

    @Value("${zap.automation.local-directory:/zap/wrk/automation}")
    private String automationLocalDirectory;

    @Value("${zap.automation.zap-directory:/zap/wrk/automation}")
    private String automationZapDirectory;

    public AutomationPlanService(EngineAutomationAccess automationAccess) {
        this.automationAccess = automationAccess;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
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
                .append("Successful: ").append(progress.successful() ? "yes" : "no").append('\n')
                .append("Info Messages: ").append(progress.info().size()).append('\n')
                .append("Warnings: ").append(progress.warnings().size()).append('\n')
                .append("Errors: ").append(progress.errors().size()).append('\n');

        appendMessages(output, "Info", progress.info(), boundedMaxMessages);
        appendMessages(output, "Warnings", progress.warnings(), boundedMaxMessages);
        appendMessages(output, "Errors", progress.errors(), boundedMaxMessages);

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

        String runId = "plan-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path localRunDirectory = localRoot.resolve("runs").resolve(runId).normalize();
        Path localArtifactsDirectory = localRunDirectory.resolve("artifacts").normalize();
        Path zapRunDirectory = zapRoot.resolve("runs").resolve(runId).normalize();
        Path zapArtifactsDirectory = zapRunDirectory.resolve("artifacts").normalize();

        createDirectories(localArtifactsDirectory);

        Map<String, Object> normalizedPlan = parseYamlObject(sourcePlanContent);
        validatePlanContexts(normalizedPlan);
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

    private void validatePlanContexts(Map<String, Object> plan) {
        Map<String, Object> env = childMap(plan.get("env"));
        List<Map<String, Object>> contexts = childMapList(env.get("contexts"));
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException("Automation plans must define at least one env.contexts entry");
        }
    }

    private Map<String, Object> parseYamlObject(String yamlText) {
        if (!hasText(yamlText)) {
            throw new IllegalArgumentException("Automation plan content cannot be blank");
        }

        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Automation plan YAML must be a mapping at the root");
        }
        return normalizeMap(rawMap);
    }

    private String dumpYaml(Map<String, Object> plan) {
        DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        return new Yaml(options).dump(plan);
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
        output.append("Artifact Type: ").append(type).append('\n')
                .append("Path: ").append(artifactPath).append('\n')
                .append("Exists: ").append(Files.exists(artifactPath) ? "yes" : "no").append('\n');
        if (!Files.exists(artifactPath) || !Files.isRegularFile(artifactPath)) {
            return 1;
        }

        try {
            output.append("Size: ").append(Files.size(artifactPath)).append(" bytes\n");
            if (reportFilePrefix != null) {
                output.append("Declared Prefix: ").append(reportFilePrefix).append('\n');
            }
            if (template != null) {
                output.append("Template: ").append(template).append('\n');
            }
            if (remainingPreviewChars > 0 && isPreviewableTextFile(artifactPath)) {
                int previewChars = Math.min(remainingPreviewChars, MAX_PREVIEW_PER_FILE);
                String preview = Files.readString(artifactPath, StandardCharsets.UTF_8);
                boolean truncated = preview.length() > previewChars;
                String body = truncated ? preview.substring(0, previewChars) : preview;
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
        if (!Files.exists(reportArtifact.localDirectory()) || !Files.isDirectory(reportArtifact.localDirectory())) {
            return List.of();
        }

        try (Stream<Path> children = Files.list(reportArtifact.localDirectory())) {
            Stream<Path> regularFiles = children
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()));

            if (!hasText(reportArtifact.reportFile())) {
                return regularFiles.toList();
            }

            String prefix = reportArtifact.reportFile().trim();
            return regularFiles
                    .filter(path -> matchesReportFile(path.getFileName().toString(), prefix))
                    .toList();
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
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Error reading automation plan {}: {}", path, e.getMessage(), e);
            throw new ZapApiException("Error reading automation plan file", e);
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Error writing automation plan {}: {}", path, e.getMessage(), e);
            throw new ZapApiException("Error writing automation plan file", e);
        }
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
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
