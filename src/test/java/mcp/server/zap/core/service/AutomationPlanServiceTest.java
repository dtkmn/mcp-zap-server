package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineAutomationAccess;
import mcp.server.zap.core.gateway.EngineAutomationAccess.AutomationPlanProgress;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.AddOn;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.ScannerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutomationPlanServiceTest {
    private static final String VALID_ENV = "env: {contexts: [{name: local-target, urls: ['http://example.com/']}] }\n";
    private static final int PLAN_BYTE_LIMIT = 1024 * 1024;

    private EngineAutomationAccess automationAccess;
    private EngineRuntimeAccess runtimeAccess;
    private AutomationPlanService service;
    private Path automationRoot;
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setup() throws Exception {
        automationAccess = mock(EngineAutomationAccess.class);
        runtimeAccess = mock(EngineRuntimeAccess.class);
        service = new AutomationPlanService(automationAccess, mock(UrlValidationService.class));
        service.setRuntimeAccess(runtimeAccess);
        automationRoot = Files.createDirectory(temporaryDirectory.resolve("automation"));
        ReflectionTestUtils.setField(service, "automationLocalDirectory", automationRoot.toString());
        ReflectionTestUtils.setField(service, "automationZapDirectory", "/zap/automation");
    }

    @Test
    void runAutomationPlanMaterializesNormalizedPlanAndStartsIt() throws Exception {
        when(automationAccess.runAutomationPlan(anyString())).thenReturn("7");

        String result = service.runAutomationPlan(
                null,
                """
                        env:
                          contexts:
                            - name: local-target
                              urls:
                                - http://example.com/
                        jobs:
                          - type: report
                            parameters:
                              template: traditional-json-plus
                              reportDir: custom-reports
                              reportFile: automation-report
                        """,
                "example plan.yaml"
        );

        verify(automationAccess).runAutomationPlan(anyString());
        Path runDirectory = Files.list(automationRoot.resolve("runs")).findFirst().orElseThrow();
        Path normalizedPlan = runDirectory.resolve("example-plan.yaml");
        String normalizedYaml = Files.readString(normalizedPlan);

        assertTrue(result.contains("Automation plan started."));
        assertTrue(result.contains("Plan ID: 7"));
        assertTrue(result.contains("Plan File: " + normalizedPlan));
        assertTrue(Files.isDirectory(runDirectory.resolve("custom-reports")));
        assertTrue(normalizedYaml.contains("reportDir: /zap/automation/runs/"));
        assertTrue(normalizedYaml.contains("/custom-reports"));
        assertTrue(normalizedYaml.contains("reportFile: automation-report"));
    }

    @Test
    void runAutomationPlanRejectsAmbiguousInput() {
        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.runAutomationPlan("plan.yaml", "env: {}", "plan.yaml")
        );

        assertTrue(error.getMessage().contains("Provide exactly one of planPath or planYaml"));
    }

    @ParameterizedTest
    @CsvSource({"0, 2.17.0, 0.61.0", "300, 2.17.0, 0.61.0", "300, 2.18.1, 0.62.1"})
    void supportedWholePlanDeadlinePreservesSecondsAndPerJobBudgets(
            int maxDuration, String zapVersion, String automationVersion) throws Exception {
        when(runtimeAccess.readScannerInventory()).thenReturn(deadlineInventory(zapVersion, automationVersion));
        when(automationAccess.runAutomationPlan(anyString())).thenReturn("7");
        String plan = deadlinePlan(maxDuration) + """
                jobs:
                  - type: spider
                    parameters: {context: local-target, maxDuration: 2}
                  - type: passiveScan-wait
                    parameters: {maxDuration: 1}
                """;

        String response = service.runAutomationPlan(null, plan, "deadline.yaml");

        assertTrue(response.contains("Plan ID: 7"));
        verify(runtimeAccess).readScannerInventory();
        verify(automationAccess).runAutomationPlan(anyString());
        Path materialized;
        try (var files = Files.walk(automationRoot.resolve("runs"))) {
            materialized = files.filter(path -> path.getFileName().toString().equals("deadline.yaml"))
                    .findFirst().orElseThrow();
        }
        Map<?, ?> normalized = new Yaml().load(Files.readString(materialized));
        Map<?, ?> env = (Map<?, ?>) normalized.get("env");
        assertEquals(maxDuration, ((Map<?, ?>) env.get("parameters")).get("maxDuration"));
        List<?> jobs = (List<?>) normalized.get("jobs");
        assertEquals(2, ((Map<?, ?>) ((Map<?, ?>) jobs.get(0)).get("parameters")).get("maxDuration"));
        assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) jobs.get(1)).get("parameters")).get("maxDuration"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "jobs: [{type: spider, parameters: {context: local-target, maxDuration: 1}}]"})
    void plansWithoutWholePlanDeadlineDoNotRequireRuntimeInventory(String jobs) {
        when(automationAccess.runAutomationPlan(anyString())).thenReturn("7");

        assertTrue(service.runAutomationPlan(null, VALID_ENV + jobs, "legacy.yaml").contains("Plan ID: 7"));

        verify(automationAccess).runAutomationPlan(anyString());
        verifyNoInteractions(runtimeAccess);
    }

    @ParameterizedTest
    @MethodSource("unsupportedDeadlineInventories")
    void unsupportedWholePlanDeadlineRejectsInlineAndFileBeforeMaterialization(ScannerInventory inventory) throws Exception {
        when(runtimeAccess.readScannerInventory()).thenReturn(inventory);
        String plan = deadlinePlan(0) + "jobs: []\n";

        IllegalArgumentException inlineError = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(null, plan, "inline.yaml"));
        Path source = automationRoot.resolve("source.yaml");
        Files.writeString(source, plan);
        IllegalArgumentException fileError = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(source.toString(), null, null));

        assertTrue(inlineError.getMessage().contains("maxDuration"), inlineError.getMessage());
        assertTrue(fileError.getMessage().contains("maxDuration"), fileError.getMessage());
        verifyNoInteractions(automationAccess);
        try (var files = Files.walk(automationRoot)) {
            assertFalse(files.filter(Files::isRegularFile).anyMatch(path -> !path.equals(source)),
                    "Unsupported deadlines must not materialize a run");
        }
    }

    static Stream<ScannerInventory> unsupportedDeadlineInventories() {
        return Stream.of(
                null,
                ScannerInventory.unknown(),
                new ScannerInventory("2.17.0", List.of(), true),
                new ScannerInventory("2.17.0", List.of(new AddOn("automation", "0.61.0", "beta")), false),
                new ScannerInventory("2.17.0", List.of(new AddOn("automation", "0.61.0", "beta"),
                        new AddOn("automation", "0.62.0", "beta")), true),
                deadlineInventory("2.16.0", "0.61.0"),
                deadlineInventory("2.17.0", "0.60.0"),
                deadlineInventory("development", "0.61.0"),
                deadlineInventory("2.17.0", "development"),
                deadlineInventory("2.17.0-SNAPSHOT", "0.61.0"),
                deadlineInventory("2.17.0", "0.61.0-SNAPSHOT")
        );
    }

    @Test
    void unavailableDeadlineInventoryDoesNotExposeUpstreamFailureOrMaterializePlan() throws Exception {
        when(runtimeAccess.readScannerInventory()).thenThrow(
                new IllegalStateException("private-api-key https://user:secret@example.invalid"));
        String plan = deadlinePlan(300);

        IllegalArgumentException inlineError = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(null, plan, "deadline.yaml"));
        Path source = automationRoot.resolve("source.yaml");
        Files.writeString(source, plan);
        IllegalArgumentException fileError = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(source.toString(), null, null));

        for (IllegalArgumentException error : List.of(inlineError, fileError)) {
            assertTrue(error.getMessage().contains("maxDuration"), error.getMessage());
            assertFalse(error.getMessage().contains("private-api-key"));
            assertFalse(error.getMessage().contains("user:secret"));
        }
        verifyNoInteractions(automationAccess);
        try (var files = Files.walk(automationRoot)) {
            assertFalse(files.filter(Files::isRegularFile).anyMatch(path -> !path.equals(source)));
        }
    }

    private static ScannerInventory deadlineInventory(String zapVersion, String automationVersion) {
        return new ScannerInventory(zapVersion, List.of(new AddOn("automation", automationVersion, "beta")), true);
    }

    private static String deadlinePlan(int maxDuration) {
        return "env: {contexts: [{name: local-target, urls: ['http://example.com/']}], parameters: {maxDuration: "
                + maxDuration + "}}\n";
    }

    @Test
    void getAutomationPlanStatusFormatsProgressState() {
        when(automationAccess.loadAutomationPlanProgress("11")).thenReturn(new AutomationPlanProgress(
                "2026-03-14T09:00:00Z",
                null,
                List.of("Job requestor started", "Job requestor finished"),
                List.of("Report directory was empty before run"),
                List.of()
        ));

        String result = service.getAutomationPlanStatus("11", 10);

        assertTrue(result.contains("Automation plan status:"));
        assertTrue(result.contains("Plan ID: 11"));
        assertTrue(result.contains("Completed: no"));
        assertTrue(result.contains("Successful: pending"));
        assertTrue(result.contains("Warnings: 1"));
        assertTrue(result.contains("Job requestor started"));
        assertTrue(result.contains("Report directory was empty before run"));
    }

    @ParameterizedTest
    @MethodSource("terminalProgress")
    void completedStatusDoesNotInferSuccessFromMissingErrorsOrUnclassifiedWarnings(
            List<String> warnings, List<String> errors, String success) {
        when(automationAccess.loadAutomationPlanProgress("11")).thenReturn(new AutomationPlanProgress(
                "2026-03-14T09:00:00Z", "2026-03-14T09:01:00Z", List.of(), warnings, errors));

        String result = service.getAutomationPlanStatus("11", 10);

        assertTrue(result.contains("Completed: yes"));
        assertTrue(result.contains("Successful: " + success), result);
        assertTrue(result.contains("Warnings: " + warnings.size()));
        assertTrue(result.contains("Errors: " + errors.size()));
        warnings.forEach(warning -> assertTrue(result.contains(warning)));
        errors.forEach(error -> assertTrue(result.contains(error)));
    }

    static Stream<Arguments> terminalProgress() {
        return Stream.of(
                Arguments.of(List.of(), List.of(), "unknown"),
                Arguments.of(List.of("Unclassified engine warning requiring review"), List.of(), "unknown"),
                Arguments.of(List.of(), List.of("Engine execution failed"), "no"),
                Arguments.of(List.of("Unclassified engine warning"), List.of("Engine execution failed"), "no")
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2300})
    void getAutomationPlanArtifactsListsReportOutputsAndPreview(int padding) throws Exception {
        Path runDirectory = automationRoot.resolve("runs/plan-123");
        Path artifactsDirectory = runDirectory.resolve("artifacts");
        Files.createDirectories(artifactsDirectory);
        Path planFile = runDirectory.resolve("automation-plan.yaml");
        Files.writeString(planFile, """
                env:
                  contexts:
                    - name: local-target
                      urls:
                        - http://example.com/
                jobs:
                  - type: report
                    parameters:
                      template: traditional-json-plus
                      reportDir: /zap/automation/runs/plan-123/artifacts
                      reportFile: automation-report
                """);
        Path reportFile = artifactsDirectory.resolve("automation-report.json");
        Files.writeString(reportFile, "{\"status\":\"ok\",\"padding\":\"" + "é".repeat(padding) + "\",\"end\":\"END_OF_REPORT\"}");

        String result = service.getAutomationPlanArtifacts(planFile.toString(), 10, 4000);

        assertTrue(result.contains("Automation plan artifacts:"));
        assertTrue(result.contains("Declared Report Jobs: 1"));
        assertTrue(result.contains("Artifact Type: plan"));
        assertTrue(result.contains("Artifact Type: report"));
        assertTrue(result.contains(reportFile.toString()));
        assertTrue(result.contains("{\"status\":\"ok\""));
        assertEquals(padding == 0, result.contains("END_OF_REPORT"));
        assertEquals(padding > 0, result.contains("Preview (truncated=yes)"));
    }

    @Test
    void getAutomationPlanArtifactsRejectsPathOutsideAutomationRoot() throws Exception {
        Path outsidePlan = temporaryDirectory.resolve("outside-plan.yaml");
        Files.writeString(outsidePlan, "env: {}");

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.getAutomationPlanArtifacts(outsidePlan.toString(), 10, 1000)
        );

        assertTrue(error.getMessage().contains("must stay within the configured automation workspace"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"leaf", "ancestor", "root", "hard-link"})
    void planReadersRejectFilesystemLinks(String linkType) throws Exception {
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("private"));
        Path outsidePlan = outsideDirectory.resolve("plan.yaml");
        Files.writeString(outsidePlan, VALID_ENV);
        Path planPath;
        switch (linkType) {
            case "leaf" -> {
                planPath = automationRoot.resolve("plan.yaml");
                Files.createSymbolicLink(planPath, outsidePlan);
            }
            case "ancestor" -> {
                Path linkedDirectory = automationRoot.resolve("linked");
                Files.createSymbolicLink(linkedDirectory, outsideDirectory);
                planPath = linkedDirectory.resolve("plan.yaml");
            }
            case "root" -> {
                Files.delete(automationRoot);
                Files.createSymbolicLink(automationRoot, outsideDirectory);
                planPath = automationRoot.resolve("plan.yaml");
            }
            case "hard-link" -> {
                assumeTrue(automationRoot.getFileSystem().supportedFileAttributeViews().contains("unix"));
                planPath = automationRoot.resolve("plan.yaml");
                Files.createLink(planPath, outsidePlan);
            }
            default -> throw new IllegalArgumentException(linkType);
        }

        assertAll(
                () -> assertThrowsExactly(IllegalArgumentException.class,
                        () -> service.getAutomationPlanArtifacts(planPath.toString(), 10, 1000)),
                () -> assertThrowsExactly(IllegalArgumentException.class,
                        () -> service.runAutomationPlan(planPath.toString(), null, null))
        );
        verifyNoInteractions(automationAccess);
    }

    @ParameterizedTest
    @ValueSource(strings = {"leaf", "directory", "hard-link", "dangling"})
    void getAutomationPlanArtifactsRejectsLinkedReports(String linkType) throws Exception {
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("private"));
        Path outsideReport = outsideDirectory.resolve("automation-report.json");
        Files.writeString(outsideReport, "{\"private\":true}");
        Path artifacts = automationRoot.resolve("artifacts");
        if ("directory".equals(linkType)) {
            Files.createSymbolicLink(artifacts, outsideDirectory);
        } else {
            Files.createDirectory(artifacts);
            Path reportPath = artifacts.resolve("automation-report.json");
            if ("hard-link".equals(linkType)) {
                assumeTrue(automationRoot.getFileSystem().supportedFileAttributeViews().contains("unix"));
                Files.createLink(reportPath, outsideReport);
            } else {
                Files.createSymbolicLink(reportPath, outsideReport);
                if ("dangling".equals(linkType)) {
                    Files.delete(outsideReport);
                }
            }
        }
        Path planFile = automationRoot.resolve("plan.yaml");
        Files.writeString(planFile, VALID_ENV + """
                jobs:
                  - type: report
                    parameters:
                      reportDir: /zap/automation/artifacts
                      reportFile: automation-report
                """);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.getAutomationPlanArtifacts(planFile.toString(), 10, 4000));
        verifyNoInteractions(automationAccess);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runs", "reports"})
    void runAutomationPlanRejectsLinkedOutputDirectories(String directoryName) throws Exception {
        Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("private"));
        Files.createSymbolicLink(automationRoot.resolve(directoryName), outsideDirectory);

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(null, VALID_ENV + """
                        jobs:
                          - type: report
                            parameters:
                              reportDir: /zap/automation/reports
                        """, null));
        verifyNoInteractions(automationAccess);
        try (Stream<Path> children = Files.list(outsideDirectory)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void getAutomationPlanArtifactsPreservesMissingReportDirectory() throws Exception {
        Path planFile = automationRoot.resolve("plan.yaml");
        Files.writeString(planFile, VALID_ENV + """
                jobs:
                  - type: report
                    parameters:
                      reportDir: /zap/automation/not-created-yet
                """);

        String result = service.getAutomationPlanArtifacts(planFile.toString(), 10, 4000);

        assertTrue(result.contains("Report Job 1: no generated files found yet"));
        assertFalse(Files.exists(automationRoot.resolve("not-created-yet")));
        verifyNoInteractions(automationAccess);
    }

    @Test
    void runAutomationPlanRejectsAliasExpansionBelowParserAliasLimit() {
        assertInvalidInlinePlan(aliasExpansionPlan(), "expanded nodes");
    }

    @Test
    void runAutomationPlanRejectsSequenceAliasCycle() {
        assertInvalidInlinePlan(VALID_ENV + "cycle: &cycle [*cycle]\n", "cyclic aliases");
    }

    @Test
    void runAutomationPlanRejectsMappingAliasCycle() {
        assertInvalidInlinePlan(VALID_ENV + "cycle: &cycle {self: *cycle}\n", "cyclic aliases");
    }

    @Test
    void runAutomationPlanRejectsMergeAliasCycleBeforeConstruction() {
        assertInvalidInlinePlan(VALID_ENV + "cycle: &cycle {<<: *cycle}\n", "cyclic aliases");
    }

    @Test
    void runAutomationPlanRejectsPairsValueAliasCycleBeforeConstruction() {
        assertInvalidInlinePlan(VALID_ENV + "pairs: &pairs !!pairs [{key: *pairs}]\n", "cyclic aliases");
    }

    @Test
    void runAutomationPlanCountsMergeSourcesBeforeFlattening() {
        StringBuilder yaml = new StringBuilder(VALID_ENV).append("a0: &a0 {leaf: value}\n");
        for (int i = 1; i <= 12; i++) {
            yaml.append("a").append(i).append(": &a").append(i)
                    .append(" {<<: [*a").append(i - 1).append(", *a").append(i - 1).append("]}\n");
        }

        assertInvalidInlinePlan(yaml.toString(), "expanded nodes");
    }

    @Test
    void runAutomationPlanRejectsComplexMappingKeysBeforeHashing() {
        assertInvalidInlinePlan(VALID_ENV + "? [one, two]\n: value\n", "scalar mapping keys");
    }

    @Test
    void runAutomationPlanRejectsComplexSetKeysBeforeHashing() {
        assertInvalidInlinePlan(VALID_ENV + "set: !!set\n  ? [one, two]\n", "scalar mapping keys");
    }

    @Test
    void runAutomationPlanRejectsComplexPairsKeysBeforeConstruction() {
        assertInvalidInlinePlan(VALID_ENV + "pairs: !!pairs\n  - ? [one, two]\n    : value\n", "scalar mapping keys");
    }

    @Test
    void runAutomationPlanRejectsComplexKeysInsideMergeSources() {
        assertInvalidInlinePlan(VALID_ENV + "merged:\n  <<: {? [one, two]: value}\n", "scalar mapping keys");
    }

    @Test
    void runAutomationPlanRejectsScalarAliasAmplification() {
        String yaml = VALID_ENV + "scalar: &scalar '" + "x".repeat(300_000) + "'\n"
                + "copies: [*scalar, *scalar, *scalar]\n";

        assertInvalidInlinePlan(yaml, "expanded scalar characters");
    }

    @Test
    void runAutomationPlanCountsScalarAliasesUsedAsMappingKeys() {
        String yaml = VALID_ENV + "scalar: &scalar '" + "x".repeat(400_000) + "'\n"
                + "mapping: {*scalar: one, *scalar: two}\n";

        assertInvalidInlinePlan(yaml, "expanded scalar characters");
    }

    @Test
    void runAutomationPlanCountsMappingKeysAndValuesAgainstNodeBudget() {
        StringBuilder yaml = new StringBuilder(VALID_ENV).append("values:\n");
        for (int i = 0; i < 5000; i++) {
            yaml.append("  key").append(i).append(": value\n");
        }

        assertInvalidInlinePlan(yaml.toString(), "expanded nodes");
    }

    @Test
    void runAutomationPlanRejectsExpandedAliasDepth() {
        StringBuilder yaml = new StringBuilder(VALID_ENV).append("a0: &a0 [leaf]\n");
        for (int i = 1; i <= 48; i++) {
            yaml.append("a").append(i).append(": &a").append(i)
                    .append(" [*a").append(i - 1).append("]\n");
        }

        assertInvalidInlinePlan(yaml.toString(), "expanded depth");
    }

    @Test
    void runAutomationPlanRejectsParserNestingDepth() {
        assertInvalidInlinePlan(VALID_ENV + "nested: " + "[".repeat(60) + "leaf" + "]".repeat(60), "Invalid automation plan YAML");
    }

    @Test
    void runAutomationPlanRejectsTooManyCollectionAliases() {
        String yaml = VALID_ENV + "base: &base [leaf]\ncopies: ["
                + String.join(", ", java.util.Collections.nCopies(51, "*base")) + "]\n";

        assertInvalidInlinePlan(yaml, "Invalid automation plan YAML");
    }

    @Test
    void runAutomationPlanRejectsOversizedInlineInputBeforeBlankCheck() {
        assertInvalidInlinePlan(" ".repeat(PLAN_BYTE_LIMIT + 1), "UTF-8 bytes");
    }

    @Test
    void runAutomationPlanBoundsUtf8BytesRatherThanOnlyCharacterCount() {
        assertInvalidInlinePlan(VALID_ENV + "value: '" + "\u20ac".repeat(400_000) + "'\n", "UTF-8 bytes");
    }

    @Test
    void runAutomationPlanRejectsNormalizedCopyExceedingInputByteLimit() throws Exception {
        String yaml = VALID_ENV + "base: &base {label: '" + "\u20ac".repeat(200_000) + "'}\ncopy: *base\n";

        assertInvalidInlinePlan(yaml, "UTF-8 bytes");
        try (var files = Files.walk(automationRoot)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".yaml")));
        }
    }

    @Test
    void runAutomationPlanRejectsIndentationAmplificationDuringNormalization() {
        String yaml = VALID_ENV + "nested: " + "{child: ".repeat(30)
                + "\"" + "x\\n".repeat(30_000) + "\"" + "}".repeat(30) + "\n";

        assertInvalidInlinePlan(yaml, "UTF-8 bytes");
    }

    @Test
    void runAutomationPlanRejectsNormalizedCopyExceedingNodeBudget() {
        // The source has exactly 10,000 nodes. Normalizing the report job adds four more.
        String yaml = VALID_ENV + "jobs: [{type: report}]\nvalues: ["
                + String.join(", ", java.util.Collections.nCopies(9982, "x")) + "]\n";

        assertInvalidInlinePlan(yaml, "expanded nodes");
    }

    @Test
    void runAutomationPlanRejectsOversizedWorkspaceFile() throws Exception {
        Path planFile = automationRoot.resolve("oversized.yaml");
        Files.writeString(planFile, VALID_ENV + "value: '" + "x".repeat(PLAN_BYTE_LIMIT) + "'\n");

        IllegalArgumentException error = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.runAutomationPlan(planFile.toString(), null, null));

        assertTrue(error.getMessage().contains("UTF-8 bytes"));
        verifyNoInteractions(automationAccess);
    }

    @Test
    void runAutomationPlanAppliesGraphBudgetToWorkspaceFile() throws Exception {
        Path planFile = automationRoot.resolve("aliases.yaml");
        Files.writeString(planFile, aliasExpansionPlan());

        IllegalArgumentException error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertThrowsExactly(IllegalArgumentException.class,
                        () -> service.runAutomationPlan(planFile.toString(), null, null)));

        assertTrue(error.getMessage().contains("expanded nodes"));
        verifyNoInteractions(automationAccess);
    }

    @Test
    void getAutomationPlanArtifactsAppliesGraphBudgetToPlanDescriptor() throws Exception {
        Path planFile = automationRoot.resolve("aliases.yaml");
        Files.writeString(planFile, aliasExpansionPlan());

        IllegalArgumentException error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertThrowsExactly(IllegalArgumentException.class,
                        () -> service.getAutomationPlanArtifacts(planFile.toString(), 10, 1000)));

        assertTrue(error.getMessage().contains("expanded nodes"));
        verifyNoInteractions(automationAccess);
    }

    @Test
    void getAutomationPlanArtifactsRejectsOversizedPlanFile() throws Exception {
        Path planFile = automationRoot.resolve("oversized.yaml");
        Files.writeString(planFile, VALID_ENV + "value: '" + "x".repeat(PLAN_BYTE_LIMIT) + "'\n");

        IllegalArgumentException error = assertThrowsExactly(IllegalArgumentException.class,
                () -> service.getAutomationPlanArtifacts(planFile.toString(), 10, 1000));

        assertTrue(error.getMessage().contains("UTF-8 bytes"));
        verifyNoInteractions(automationAccess);
    }

    @Test
    void runAutomationPlanPreservesBoundedAliasesMergesAndTypedScalars() throws Exception {
        when(automationAccess.runAutomationPlan(anyString())).thenReturn("7");
        service.runAutomationPlan(null, VALID_ENV + """
                defaults: &defaults
                  enabled: true
                  count: 3
                  ratio: 1.5
                  empty: null
                  label: 'on'
                urls: &urls [http://example.com/]
                copy: *urls
                unicode: '😀é'
                duplicate: first
                duplicate: last
                scalarKeys: {7: integer-key, true: boolean-key, null: null-key}
                safeSet: !!set {one: null, two: null}
                safePairs: !!pairs [{key: value}]
                safeOrdered: !!omap [{first: one}, {second: two}]
                jobs:
                  - type: report
                    parameters:
                      <<: *defaults
                      count: 4
                      template: traditional-json-plus
                      reportFile: compatible-report
                """, "compatible.yaml");

        Path runDirectory;
        try (var runs = Files.list(automationRoot.resolve("runs"))) {
            runDirectory = runs.findFirst().orElseThrow();
        }
        Map<String, Object> normalized = new Yaml().load(Files.readString(runDirectory.resolve("compatible.yaml")));
        assertEquals(normalized.get("urls"), normalized.get("copy"));
        assertEquals("😀é", normalized.get("unicode"));
        assertEquals("last", normalized.get("duplicate"));
        assertEquals(Map.of("7", "integer-key", "true", "boolean-key", "null", "null-key"), normalized.get("scalarKeys"));
        assertEquals(java.util.Set.of("one", "two"), normalized.get("safeSet"));
        assertEquals(List.of(List.of("key", "value")), normalized.get("safePairs"));
        assertEquals(Map.of("first", "one", "second", "two"), normalized.get("safeOrdered"));
        Map<?, ?> report = (Map<?, ?>) ((List<?>) normalized.get("jobs")).getFirst();
        Map<?, ?> parameters = (Map<?, ?>) report.get("parameters");
        assertEquals(true, parameters.get("enabled"));
        assertEquals(4, parameters.get("count"));
        assertEquals(1.5, parameters.get("ratio"));
        assertTrue(parameters.containsKey("empty"));
        assertEquals(null, parameters.get("empty"));
        assertEquals("on", parameters.get("label"));
        String artifacts = service.getAutomationPlanArtifacts(runDirectory.resolve("compatible.yaml").toString(), 10, 1000);
        assertTrue(artifacts.contains("Declared Report Jobs: 1"));
        assertTrue(artifacts.contains("compatible-report"));
        verify(automationAccess).runAutomationPlan(anyString());
    }

    @Test
    void runAutomationPlanPreservesRootSingleDocumentAndContextRequirements() {
        assertInvalidInlinePlan("- one\n- two\n", "mapping at the root");
        assertInvalidInlinePlan("# no document\n", "mapping at the root");
        assertInvalidInlinePlan(VALID_ENV + "---\n" + VALID_ENV, "Invalid automation plan YAML");
        assertInvalidInlinePlan("env: {}\n", "env.contexts");
        assertInvalidInlinePlan(VALID_ENV + "object: !!java.util.ArrayList []\n", "Invalid automation plan YAML");
    }

    private void assertInvalidInlinePlan(String yaml, String messageFragment) {
        IllegalArgumentException error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> assertThrowsExactly(IllegalArgumentException.class,
                        () -> service.runAutomationPlan(null, yaml, "invalid.yaml")));

        assertTrue(error.getMessage().contains(messageFragment), error.getMessage());
        verifyNoInteractions(automationAccess);
    }

    private String aliasExpansionPlan() {
        StringBuilder yaml = new StringBuilder(VALID_ENV).append("a0: &a0 [leaf]\n");
        for (int i = 1; i <= 14; i++) {
            yaml.append("a").append(i).append(": &a").append(i)
                    .append(" [*a").append(i - 1).append(", *a").append(i - 1).append("]\n");
        }
        return yaml.toString();
    }
}
