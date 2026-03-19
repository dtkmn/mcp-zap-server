package mcp.server.zap;

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
import mcp.server.zap.core.service.ExpertQueueMcpToolsService;
import mcp.server.zap.core.service.ExpertResultsMcpToolsService;
import mcp.server.zap.core.service.ExpertToolGroup;
import mcp.server.zap.core.service.FindingsService;
import mcp.server.zap.core.service.GuidedExecutionModeResolver;
import mcp.server.zap.core.service.GuidedScanWorkflowService;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.OpenApiService;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import mcp.server.zap.core.service.PassiveScanService;
import mcp.server.zap.core.service.ReportService;
import mcp.server.zap.core.service.ScanJobQueueService;
import mcp.server.zap.core.service.SpiderScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.zaproxy.clientapi.core.ClientApi;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolSurfaceProviderTest {

    private GuidedSecurityToolsService guidedSecurityToolsService;
    private PassiveScanMcpToolsService passiveScanMcpToolsService;
    private List<ExpertToolGroup> expertToolGroups;

    @BeforeEach
    void setUp() {
        guidedSecurityToolsService = new GuidedSecurityToolsService(
                new GuidedScanWorkflowService(
                        mock(GuidedExecutionModeResolver.class),
                        mock(SpiderScanService.class),
                        mock(AjaxSpiderService.class),
                        mock(ActiveScanService.class),
                        mock(ScanJobQueueService.class)
                ),
                mock(ReportService.class),
                mock(FindingsService.class),
                mock(OpenApiService.class)
        );
        passiveScanMcpToolsService = new PassiveScanMcpToolsService(new PassiveScanService(mock(ClientApi.class)));
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
                new ExpertAuthMcpToolsService(mock(ContextUserService.class)),
                new ExpertAutomationMcpToolsService(mock(AutomationPlanService.class))
        );
    }

    @Test
    void guidedSurfaceRegistersOnlyGuidedAndPassiveTools() {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.GUIDED);

        ToolCallbackProvider provider = new McpServerApplication().toolCallbackProvider(
                properties,
                guidedSecurityToolsService,
                passiveScanMcpToolsService,
                expertToolGroups
        );

        Set<String> toolNames = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertThat(toolNames).contains(
                "zap_target_import",
                "zap_crawl_start",
                "zap_crawl_status",
                "zap_crawl_stop",
                "zap_attack_start",
                "zap_attack_status",
                "zap_attack_stop",
                "zap_findings_summary",
                "zap_findings_details",
                "zap_report_generate",
                "zap_passive_scan_status",
                "zap_passive_scan_wait"
        );
        assertThat(toolNames).doesNotContain(
                "zap_spider_start",
                "zap_ajax_spider",
                "zap_queue_spider_scan",
                "zap_active_scan_start",
                "zap_generate_report",
                "zap_get_findings_summary",
                "zap_import_openapi_spec_url"
        );
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
        assertThat(hasToolContextParameters(GuidedScanWorkflowService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertInventoryMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertDirectScanMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertQueueMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertImportMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertResultsMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertAuthMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(ExpertAutomationMcpToolsService.class)).isFalse();
        assertThat(hasToolContextParameters(PassiveScanMcpToolsService.class)).isFalse();
    }

    @Test
    void guidedCrawlToolExposesSelectionGuidanceForAgents() throws Exception {
        Method method = GuidedSecurityToolsService.class.getDeclaredMethod(
                "startCrawl",
                String.class,
                String.class,
                String.class
        );

        Tool tool = method.getAnnotation(Tool.class);
        ToolParam strategyParam = method.getParameters()[1].getAnnotation(ToolParam.class);
        ToolParam targetUrlParam = method.getParameters()[0].getAnnotation(ToolParam.class);

        assertThat(tool.description()).contains("direct", "queued", "http", "browser");
        assertThat(strategyParam.description()).contains("auto", "http", "browser", "SPAs");
        assertThat(targetUrlParam.description()).contains("host", "root URL");
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
}
