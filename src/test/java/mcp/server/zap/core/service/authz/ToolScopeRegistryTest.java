package mcp.server.zap.core.service.authz;

import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolSurface;
import mcp.server.zap.RetiredExpertTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolScopeRegistryTest {
    private final ToolScopeRegistry registry = new ToolScopeRegistry();

    @Test
    void describesOnlyTheGuidedRuntimeSurface() {
        assertThat(registry.getToolRegistry().names()).hasSize(28)
                .containsExactlyInAnyOrderElementsOf(registry.getRequiredScopesByTool().keySet());
        assertThat(registry.getToolRegistry().descriptorsForSurface(McpToolSurface.GUIDED))
                .map(McpToolDescriptor::name).contains("zap_crawl_start", "zap_attack_start", "zap_report_read",
                        "zap_findings_snapshot", "zap_findings_diff");
        assertThat(registry.getToolRegistry().descriptorsForSurface(McpToolSurface.EXPERT)).isEmpty();
    }

    @Test
    void onlyGuidedStartsCarryScanAdmissionCapability() {
        assertThat(registry.getToolNamesByCapability(ToolScopeRegistry.GUIDED_SCAN_CAPABILITY))
                .containsExactlyInAnyOrder("zap_crawl_start", "zap_attack_start");
        assertThat(registry.hasCapability("zap_report_read", ToolScopeRegistry.GUIDED_SCAN_CAPABILITY)).isFalse();
        for (String retired : RetiredExpertTools.NAMES) assertThat(registry.getRequiredScopes(retired)).isNull();
    }

    @Test
    void retainedFindingsAndLifecycleToolsKeepTheirReadStopAndOperatorScopes() {
        assertThat(registry.getRequiredScopes("zap_findings_snapshot")).containsExactly("zap:alerts:read");
        assertThat(registry.getRequiredScopes("zap_findings_diff")).containsExactly("zap:alerts:read");
        assertThat(registry.getRequiredScopes("zap_crawl_status")).containsExactly("zap:scan:read");
        assertThat(registry.getRequiredScopes("zap_crawl_stop")).containsExactly("zap:scan:stop");
        assertThat(registry.getRequiredScopes("zap_scan_job_reconcile")).containsExactly("zap:scan:queue:reconcile");
    }
}
