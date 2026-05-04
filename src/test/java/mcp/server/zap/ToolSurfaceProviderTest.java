package mcp.server.zap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.configuration.ToolSurfaceProperties;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.AutomationPlanService;
import mcp.server.zap.core.service.ContextUserService;
import mcp.server.zap.core.service.CoreService;
import mcp.server.zap.core.service.ExpertAuthMcpToolsService;
import mcp.server.zap.core.service.ExpertAutomationMcpToolsService;
import mcp.server.zap.core.service.ExpertDirectScanMcpToolsService;
import mcp.server.zap.core.service.ExpertImportMcpToolsService;
import mcp.server.zap.core.service.ExpertInventoryMcpToolsService;
import mcp.server.zap.core.service.ExpertPolicyMcpToolsService;
import mcp.server.zap.core.service.ExpertQueueMcpToolsService;
import mcp.server.zap.core.service.ExpertResultsMcpToolsService;
import mcp.server.zap.core.service.ExpertToolGroup;
import mcp.server.zap.core.service.FindingsService;
import mcp.server.zap.core.service.GuidedAuthSessionMcpToolsService;
import mcp.server.zap.core.service.GuidedExecutionModeResolver;
import mcp.server.zap.core.service.GuidedScanWorkflowService;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.OpenApiService;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import mcp.server.zap.core.service.PassiveScanService;
import mcp.server.zap.core.service.ReportService;
import mcp.server.zap.core.service.ScanHistoryMcpToolsService;
import mcp.server.zap.core.service.ScanJobQueueService;
import mcp.server.zap.core.service.SpiderScanService;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.core.service.policy.PolicyDryRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolSurfaceProviderTest {

    private static final Path TOOL_SURFACE_SNAPSHOT_ROOT = Path.of("src/test/resources/mcp-tool-surface");

    private GuidedSecurityToolsService guidedSecurityToolsService;
    private GuidedAuthSessionMcpToolsService guidedAuthSessionMcpToolsService;
    private PassiveScanMcpToolsService passiveScanMcpToolsService;
    private ScanHistoryMcpToolsService scanHistoryMcpToolsService;
    private List<ExpertToolGroup> expertToolGroups;

    @BeforeEach
    void setUp() {
        guidedSecurityToolsService = new GuidedSecurityToolsService(
                new GuidedScanWorkflowService(
                        mock(GuidedExecutionModeResolver.class),
                        mock(SpiderScanService.class),
                        mock(AjaxSpiderService.class),
                        mock(ActiveScanService.class),
                        mock(ScanJobQueueService.class),
                        mock(GuidedAuthSessionService.class),
                        new ZapEngineAdapter(),
                        new GatewayRecordFactory()
                ),
                mock(ReportService.class),
                mock(FindingsService.class),
                mock(OpenApiService.class),
                new ZapEngineAdapter(),
                new GatewayRecordFactory()
        );
        guidedAuthSessionMcpToolsService = new GuidedAuthSessionMcpToolsService(
                mock(mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService.class)
        );
        passiveScanMcpToolsService = new PassiveScanMcpToolsService(
                new PassiveScanService(mock(EnginePassiveScanAccess.class))
        );
        scanHistoryMcpToolsService = new ScanHistoryMcpToolsService(mock(ScanHistoryLedgerService.class));
        expertToolGroups = List.of(
                new ExpertInventoryMcpToolsService(mock(CoreService.class)),
                new ExpertDirectScanMcpToolsService(
                        mock(ActiveScanService.class),
                        mock(SpiderScanService.class),
                        mock(AjaxSpiderService.class)
                ),
                new ExpertQueueMcpToolsService(mock(ScanJobQueueService.class)),
                new ExpertImportMcpToolsService(mock(OpenApiService.class)),
                new ExpertResultsMcpToolsService(
                        mock(FindingsService.class),
                        mock(ReportService.class)
                ),
                new ExpertPolicyMcpToolsService(
                        new PolicyDryRunService(new ObjectMapper(), new ToolScopeRegistry())
                ),
                new ExpertAuthMcpToolsService(mock(ContextUserService.class)),
                new ExpertAutomationMcpToolsService(mock(AutomationPlanService.class))
        );
    }

    @Test
    void guidedSurfaceRegistersOnlyGuidedAndPassiveTools() throws IOException {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.GUIDED);

        ToolCallbackProvider provider = new McpServerApplication().toolCallbackProvider(
                properties,
                guidedSecurityToolsService,
                guidedAuthSessionMcpToolsService,
                passiveScanMcpToolsService,
                scanHistoryMcpToolsService,
                expertToolGroups
        );

        Set<String> toolNames = toolNames(provider);

        assertThat(toolNames).containsExactlyInAnyOrderElementsOf(snapshotToolNames("guided-tools.txt"));
    }

    @Test
    void expertSurfaceKeepsRepresentativeGuidedAndExpertZapTools() {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.EXPERT);

        ToolCallbackProvider provider = new McpServerApplication().toolCallbackProvider(
                properties,
                guidedSecurityToolsService,
                guidedAuthSessionMcpToolsService,
                passiveScanMcpToolsService,
                scanHistoryMcpToolsService,
                expertToolGroups
        );

        Set<String> toolNames = toolNames(provider);

        assertThat(toolNames).contains(
                "zap_attack_start",
                "zap_findings_summary",
                "zap_report_generate",
                "zap_active_scan_start",
                "zap_queue_active_scan",
                "zap_get_findings_summary",
                "zap_generate_report",
                "zap_policy_dry_run"
        );
    }

    @Test
    void expertSurfaceSnapshotRemainsStableDuringGatewayAdapterWork() throws IOException {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.EXPERT);

        ToolCallbackProvider provider = new McpServerApplication().toolCallbackProvider(
                properties,
                guidedSecurityToolsService,
                guidedAuthSessionMcpToolsService,
                passiveScanMcpToolsService,
                scanHistoryMcpToolsService,
                expertToolGroups
        );

        assertThat(toolNames(provider)).containsExactlyInAnyOrderElementsOf(snapshotToolNames("expert-tools.txt"));
    }

    @Test
    void coreServicesNoLongerDeclareToolAnnotations() {
        assertThat(Arrays.stream(ActiveScanService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(SpiderScanService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(AjaxSpiderService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(ScanJobQueueService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(CoreService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(OpenApiService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(FindingsService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(ReportService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(ContextUserService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(AutomationPlanService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(PassiveScanService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
        assertThat(Arrays.stream(GuidedScanWorkflowService.class.getDeclaredMethods()).anyMatch(method -> method.isAnnotationPresent(Tool.class))).isFalse();
    }

    @Test
    void coreServicesNoLongerDeclareToolParamAnnotations() {
        assertThat(hasToolParamAnnotations(ActiveScanService.class)).isFalse();
        assertThat(hasToolParamAnnotations(SpiderScanService.class)).isFalse();
        assertThat(hasToolParamAnnotations(AjaxSpiderService.class)).isFalse();
        assertThat(hasToolParamAnnotations(ScanJobQueueService.class)).isFalse();
        assertThat(hasToolParamAnnotations(CoreService.class)).isFalse();
        assertThat(hasToolParamAnnotations(OpenApiService.class)).isFalse();
        assertThat(hasToolParamAnnotations(FindingsService.class)).isFalse();
        assertThat(hasToolParamAnnotations(ReportService.class)).isFalse();
        assertThat(hasToolParamAnnotations(ContextUserService.class)).isFalse();
        assertThat(hasToolParamAnnotations(AutomationPlanService.class)).isFalse();
        assertThat(hasToolParamAnnotations(PassiveScanService.class)).isFalse();
        assertThat(hasToolParamAnnotations(GuidedScanWorkflowService.class)).isFalse();
    }

    @Test
    void servicesNoLongerDependOnToolContextParameters() {
        assertThat(hasToolContextParameters(ActiveScanService.class)).isFalse();
        assertThat(hasToolContextParameters(SpiderScanService.class)).isFalse();
        assertThat(hasToolContextParameters(AjaxSpiderService.class)).isFalse();
        assertThat(hasToolContextParameters(ScanJobQueueService.class)).isFalse();
        assertThat(hasToolContextParameters(AutomationPlanService.class)).isFalse();
        assertThat(hasToolContextParameters(GuidedSecurityToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(GuidedAuthSessionMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(GuidedScanWorkflowService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertInventoryMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertDirectScanMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertQueueMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertImportMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertResultsMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertPolicyMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertAuthMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertAutomationMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(PassiveScanMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ScanHistoryMcpToolsService.class)).isFalse();
    }

    @Test
    void guidedCrawlToolExposesSelectionGuidanceForAgents() throws Exception {
        Method method = GuidedSecurityToolsService.class.getDeclaredMethod(
                "startCrawl",
                String.class,
                String.class,
                String.class,
                String.class
        );

        Tool tool = method.getAnnotation(Tool.class);
        ToolParam strategyParam = method.getParameters()[1].getAnnotation(ToolParam.class);
        ToolParam targetUrlParam = method.getParameters()[0].getAnnotation(ToolParam.class);
        ToolParam authSessionParam = method.getParameters()[3].getAnnotation(ToolParam.class);

        assertThat(tool.description()).contains("direct", "queued", "http", "browser", "authSessionId", "form-login");
        assertThat(strategyParam.description()).contains("auto", "http", "browser", "SPAs");
        assertThat(targetUrlParam.description()).contains("host", "root URL");
        assertThat(authSessionParam.description()).contains("prepared auth session ID", "form-login", "browser");
    }

    @Test
    void guidedResultsToolsExposeTriageAndScopingGuidanceForAgents() throws Exception {
        Method summaryMethod = GuidedSecurityToolsService.class.getDeclaredMethod(
                "getGuidedFindingsSummary",
                String.class
        );
        Method detailsMethod = GuidedSecurityToolsService.class.getDeclaredMethod(
                "getGuidedFindingsDetails",
                String.class,
                String.class,
                String.class,
                Boolean.class,
                Integer.class
        );
        Method reportMethod = GuidedSecurityToolsService.class.getDeclaredMethod(
                "generateGuidedReport",
                String.class,
                String.class,
                String.class
        );

        Tool summaryTool = summaryMethod.getAnnotation(Tool.class);
        Tool detailsTool = detailsMethod.getAnnotation(Tool.class);
        Tool reportTool = reportMethod.getAnnotation(Tool.class);
        ToolParam summaryBaseUrlParam = summaryMethod.getParameters()[0].getAnnotation(ToolParam.class);
        ToolParam includeInstancesParam = detailsMethod.getParameters()[3].getAnnotation(ToolParam.class);
        ToolParam reportFormatParam = reportMethod.getParameters()[1].getAnnotation(ToolParam.class);

        assertThat(summaryTool.description()).contains("first-pass", "triage", "baseUrl");
        assertThat(detailsTool.description()).contains("Drill", "includeInstances", "URLs", "evidence");
        assertThat(reportTool.description()).contains("human-shareable", "artifact", "triage");
        assertThat(summaryBaseUrlParam.description()).contains("host", "path");
        assertThat(includeInstancesParam.description()).contains("URLs", "evidence");
        assertThat(reportFormatParam.description()).contains("html", "json");
    }

    private boolean hasToolParamAnnotations(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .anyMatch(parameter -> parameter.isAnnotationPresent(ToolParam.class));
    }

    private boolean hasToolContextParameters(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(parameterType -> parameterType == ToolContext.class);
    }

    private Set<String> toolNames(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    private List<String> snapshotToolNames(String fileName) throws IOException {
        return Files.readAllLines(TOOL_SURFACE_SNAPSHOT_ROOT.resolve(fileName)).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
