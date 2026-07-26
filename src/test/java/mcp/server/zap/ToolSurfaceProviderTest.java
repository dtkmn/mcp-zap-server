package mcp.server.zap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.server.zap.core.configuration.ToolSurfaceProperties;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
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
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.core.service.policy.PolicyDryRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

class ToolSurfaceProviderTest {

    private ToolScopeRegistry toolScopeRegistry;
    private GuidedSecurityToolsService guidedSecurityToolsService;
    private GuidedAuthSessionMcpToolsService guidedAuthSessionMcpToolsService;
    private PassiveScanMcpToolsService passiveScanMcpToolsService;
    private ScanHistoryMcpToolsService scanHistoryMcpToolsService;
    private List<ExpertToolGroup> expertToolGroups;

    @BeforeEach
    void setUp() {
        toolScopeRegistry = new ToolScopeRegistry();
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
                mock(GuidedAuthSessionService.class)
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
                        new PolicyDryRunService(new ObjectMapper(), toolScopeRegistry)
                ),
                new ExpertAuthMcpToolsService(mock(ContextUserService.class)),
                new ExpertAutomationMcpToolsService(mock(AutomationPlanService.class))
        );
    }

    @Test
    void guidedSurfaceMatchesAuthoritativeToolRegistry() {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.GUIDED);

        ToolCallbackProvider provider = provider(properties);

        assertThat(toolNames(provider))
                .containsExactlyInAnyOrderElementsOf(toolNamesForSurface(McpToolSurface.GUIDED));
    }

    @Test
    void expertSurfaceMatchesAuthoritativeToolRegistry() {
        ToolSurfaceProperties properties = new ToolSurfaceProperties();
        properties.setSurface(ToolSurfaceProperties.Surface.EXPERT);

        ToolCallbackProvider provider = provider(properties);

        assertThat(toolNames(provider))
                .containsExactlyInAnyOrderElementsOf(toolScopeRegistry.getToolRegistry().names());
    }

    private ToolCallbackProvider provider(ToolSurfaceProperties properties) {
        return new McpServerApplication().toolCallbackProvider(
                properties,
                guidedSecurityToolsService,
                guidedAuthSessionMcpToolsService,
                passiveScanMcpToolsService,
                scanHistoryMcpToolsService,
                expertToolGroups
        );
    }

    private Set<String> toolNamesForSurface(McpToolSurface surface) {
        return toolScopeRegistry.getToolRegistry().descriptorsForSurface(surface).stream()
                .map(McpToolDescriptor::name)
                .collect(Collectors.toSet());
    }

    private Set<String> toolNames(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
