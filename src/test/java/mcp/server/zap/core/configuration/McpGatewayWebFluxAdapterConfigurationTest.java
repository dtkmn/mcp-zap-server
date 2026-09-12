package mcp.server.zap.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.junit.jupiter.api.Test;
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
    void registeredCallbackWithoutAnExistingDescriptorFailsClosed() {
        assertThatThrownBy(() -> configuration.mcpActiveToolRegistry(
                provider("zap_passive_scan_status", "unmapped_test_tool"), scopeRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown MCP tool: unmapped_test_tool");
    }

    @Test
    void duplicateCallbackNamesFailInsteadOfBeingSilentlyDeduplicated() {
        assertThatThrownBy(() -> configuration.mcpActiveToolRegistry(
                provider("zap_passive_scan_status", "zap_passive_scan_status"), scopeRegistry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate MCP tool descriptor: zap_passive_scan_status");
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
