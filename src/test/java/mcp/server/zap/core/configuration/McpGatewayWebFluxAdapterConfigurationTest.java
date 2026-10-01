package mcp.server.zap.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import mcp.gateway.core.audit.GatewayAuditSink;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.McpAbuseProtectionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpGatewayWebFluxAdapterConfigurationTest {
    private final McpGatewayWebFluxAdapterConfiguration configuration =
            new McpGatewayWebFluxAdapterConfiguration();
    private final ToolScopeRegistry scopeRegistry = new ToolScopeRegistry();

    @Test
    void activeRegistryContainsExactlyRegisteredCallbacksNotEveryMappedTool() {
        McpToolRegistry registry = configuration.mcpActiveToolRegistry(
                provider("zap_crawl_start", "zap_passive_scan_status"), scopeRegistry);

        assertThat(registry.names())
                .containsExactlyInAnyOrder("zap_crawl_start", "zap_passive_scan_status")
                .doesNotContain("zap_spider_status", "zap_attack_start");
        assertThat(scopeRegistry.getToolRegistry().names())
                .contains("zap_spider_status", "zap_attack_start");
    }

    @Test
    void activeRegistryPreservesToolCapabilities() {
        McpToolRegistry registry = configuration.mcpActiveToolRegistry(
                provider("zap_crawl_start", "zap_active_scan_start"), scopeRegistry);

        assertThat(registry.hasCapability("zap_crawl_start", ToolScopeRegistry.GUIDED_SCAN_CAPABILITY))
                .isTrue();
        assertThat(registry.hasCapability("zap_active_scan_start", ToolScopeRegistry.DIRECT_SCAN_CAPABILITY))
                .isTrue();
    }

    @Test
    void noRegisteredCallbacksProducesAnEmptyActiveRegistry() {
        McpToolRegistry registry = configuration.mcpActiveToolRegistry(provider(), scopeRegistry);

        assertThat(registry.names()).isEmpty();
        assertThat(registry.descriptors()).isEmpty();
    }

    @Test
    void reportsAllMissingActiveMappingsInSortedOrderBeforeReturningARegistry() {
        assertThatThrownBy(() -> configuration.mcpActiveToolRegistry(
                provider("zeta_unmapped_tool", "zap_passive_scan_status", "alpha_unmapped_tool"), scopeRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing permission mappings for MCP tools: [alpha_unmapped_tool, zeta_unmapped_tool]");
    }

    @Test
    void missingActivePermissionMappingsPreventConfiguredApplicationStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpGatewayWebFluxAdapterConfiguration.class)
                .withBean(ToolCallbackProvider.class, () -> provider("zeta_unmapped_tool", "alpha_unmapped_tool"))
                .withBean(ToolScopeRegistry.class, () -> scopeRegistry)
                .withBean(ClientWorkspaceResolver.class, () -> mock(ClientWorkspaceResolver.class))
                .withBean(ToolAuthorizationService.class, () -> mock(ToolAuthorizationService.class))
                .withBean(McpAbuseProtectionService.class, () -> mock(McpAbuseProtectionService.class))
                .withBean(ObservabilityService.class, () -> mock(ObservabilityService.class))
                .withBean(GatewayAuditSink.class, () -> mock(GatewayAuditSink.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasRootCauseMessage("Missing permission mappings for MCP tools: [alpha_unmapped_tool, zeta_unmapped_tool]");
                });
    }

    @Test
    void duplicateCallbackNamesFailInsteadOfBeingSilentlyDeduplicated() {
        assertThatThrownBy(() -> configuration.mcpActiveToolRegistry(
                provider("zap_passive_scan_status", "zap_passive_scan_status"), scopeRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate exposed MCP tool name: zap_passive_scan_status");
    }

    private ToolCallbackProvider provider(String... names) {
        ToolCallback[] callbacks = Arrays.stream(names).map(name -> {
            ToolDefinition definition = mock(ToolDefinition.class);
            when(definition.name()).thenReturn(name);
            ToolCallback callback = mock(ToolCallback.class);
            when(callback.getToolDefinition()).thenReturn(definition);
            return callback;
        }).toArray(ToolCallback[]::new);
        return () -> callbacks;
    }
}
