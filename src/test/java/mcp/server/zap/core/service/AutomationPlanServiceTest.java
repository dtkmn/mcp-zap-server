package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineAutomationAccess;
import mcp.server.zap.core.gateway.EngineAutomationAccess.AutomationPlanProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AutomationPlanServiceTest {
    private EngineAutomationAccess automationAccess;
    private AutomationPlanService service;
    private Path automationRoot;

    @BeforeEach
    void setup() throws Exception {
        automationAccess = mock(EngineAutomationAccess.class);
        service = new AutomationPlanService(automationAccess);
        automationRoot = Files.createTempDirectory("automation-plan-service-test");
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

    @Test
    void getAutomationPlanStatusFormatsProgressState() throws Exception {
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
        assertTrue(result.contains("Warnings: 1"));
        assertTrue(result.contains("Job requestor started"));
        assertTrue(result.contains("Report directory was empty before run"));
    }

    @Test
    void getAutomationPlanArtifactsListsReportOutputsAndPreview() throws Exception {
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
        Files.writeString(reportFile, "{\"status\":\"ok\"}");

        String result = service.getAutomationPlanArtifacts(planFile.toString(), 10, 4000);

        assertTrue(result.contains("Automation plan artifacts:"));
        assertTrue(result.contains("Declared Report Jobs: 1"));
        assertTrue(result.contains("Artifact Type: plan"));
        assertTrue(result.contains("Artifact Type: report"));
        assertTrue(result.contains(reportFile.toString()));
        assertTrue(result.contains("{\"status\":\"ok\"}"));
    }

    @Test
    void getAutomationPlanArtifactsRejectsPathOutsideAutomationRoot() throws Exception {
        Path outsidePlan = Files.createTempFile("outside-automation-plan", ".yaml");
        Files.writeString(outsidePlan, "env: {}");

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.getAutomationPlanArtifacts(outsidePlan.toString(), 10, 1000)
        );

        assertTrue(error.getMessage().contains("must stay within the configured automation workspace"));
    }
}
