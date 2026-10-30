package mcp.server.zap;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.server.zap.core.service.AddonMaintenanceMcpToolsService;
import mcp.server.zap.core.service.GuidedAuthSessionMcpToolsService;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import mcp.server.zap.core.service.ScanHistoryMcpToolsService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolSurfaceProviderTest {
    private static final Set<String> EXPECTED = Set.of(
            "zap_addon_inspect",
            "zap_addon_update",
            "zap_addon_update_status",
            "zap_attack_start",
            "zap_attack_status",
            "zap_attack_stop",
            "zap_auth_session_prepare",
            "zap_auth_session_validate",
            "zap_crawl_start",
            "zap_crawl_status",
            "zap_crawl_stop",
            "zap_findings_details",
            "zap_findings_diff",
            "zap_findings_snapshot",
            "zap_findings_summary",
            "zap_passive_scan_status",
            "zap_passive_scan_wait",
            "zap_report_generate",
            "zap_report_read",
            "zap_report_read_chunk",
            "zap_scan_history_customer_handoff",
            "zap_scan_history_export",
            "zap_scan_history_get",
            "zap_scan_history_list",
            "zap_scan_history_release_evidence",
            "zap_scan_policies_list",
            "zap_scan_policy_view",
            "zap_target_import"
    );
    private static final Set<String> RECOVERY_TOOLS = Set.of(
            "zap_scan_job_reconcile", "zap_scan_job_reconciliation_status");

    @Test
    void guidedSurfaceOmitsReconciliationCallbacks() {
        Set<String> callbacks = names(provider());
        ToolScopeRegistry registry = new ToolScopeRegistry();
        assertThat(callbacks).hasSize(28).containsExactlyInAnyOrderElementsOf(EXPECTED);
        assertThat(registry.getToolRegistry().names()).hasSize(28)
                .containsExactlyInAnyOrderElementsOf(EXPECTED).doesNotContainAnyElementsOf(RECOVERY_TOOLS);
        assertThat(registry.getToolRegistry().descriptorsForSurface(McpToolSurface.EXPERT)).isEmpty();
    }

    @Test
    void retiredExpertAliasesAreNeitherRegisteredNorDescribed() {
        assertThat(RetiredExpertTools.NAMES).hasSize(53);
        assertThat(names(provider())).doesNotContainAnyElementsOf(RetiredExpertTools.NAMES);
        assertThat(new ToolScopeRegistry().getToolRegistry().names()).doesNotContainAnyElementsOf(RetiredExpertTools.NAMES);
    }

    private ToolCallbackProvider provider() {
        return new McpServerApplication().toolCallbackProvider(
                mock(GuidedSecurityToolsService.class), mock(GuidedAuthSessionMcpToolsService.class),
                mock(PassiveScanMcpToolsService.class), mock(ScanHistoryMcpToolsService.class),
                mock(AddonMaintenanceMcpToolsService.class));
    }

    private Set<String> names(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks()).map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
