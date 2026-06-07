package mcp.server.zap.core.service.authz;

import static org.assertj.core.api.Assertions.assertThat;

import mcp.gateway.core.tool.McpToolDescriptor;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import org.junit.jupiter.api.Test;

class ToolScopeRegistryTest {

    private final ToolScopeRegistry registry = new ToolScopeRegistry();

    @Test
    void exposesCoreToolRegistryForTheRuntimeToolSurface() {
        McpToolRegistry toolRegistry = registry.getToolRegistry();

        assertThat(toolRegistry.names()).containsAll(registry.getRequiredScopesByTool().keySet());
        assertThat(toolRegistry.descriptorsForSurface(McpToolSurface.GUIDED))
                .map(McpToolDescriptor::name)
                .contains("zap_crawl_start", "zap_attack_start", "zap_report_read");
        assertThat(toolRegistry.descriptorsForSurface(McpToolSurface.EXPERT))
                .map(McpToolDescriptor::name)
                .contains("zap_active_scan_start", "zap_queue_active_scan", "zap_policy_dry_run");
    }

    @Test
    void classifiesProtectionSensitiveToolsThroughCoreCapabilities() {
        assertThat(registry.hasCapability("zap_crawl_start", ToolScopeRegistry.GUIDED_SCAN_CAPABILITY)).isTrue();
        assertThat(registry.hasCapability("zap_attack_start", ToolScopeRegistry.GUIDED_SCAN_CAPABILITY)).isTrue();
        assertThat(registry.hasCapability("zap_active_scan_start", ToolScopeRegistry.DIRECT_SCAN_CAPABILITY)).isTrue();
        assertThat(registry.hasCapability("zap_queue_active_scan", ToolScopeRegistry.QUEUE_ADMISSION_CAPABILITY)).isTrue();
        assertThat(registry.hasCapability("zap_scan_job_retry", ToolScopeRegistry.QUEUE_ADMISSION_CAPABILITY)).isTrue();
        assertThat(registry.hasCapability("zap_automation_plan_run", ToolScopeRegistry.AUTOMATION_EXECUTION_CAPABILITY)).isTrue();

        assertThat(registry.hasCapability("zap_report_read", ToolScopeRegistry.DIRECT_SCAN_CAPABILITY)).isFalse();
        assertThat(registry.hasCapability("zap_unknown_tool", ToolScopeRegistry.QUEUE_ADMISSION_CAPABILITY)).isFalse();
    }
}
