package mcp.server.zap.core.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class ToolScopeRegistryValidatorTest {

    @Test
    void exposedToolWithoutPermissionMappingFailsStartup() {
        ToolScopeRegistryValidator validator = validator(Set.of("alpha_tool"));

        assertThatThrownBy(validator::validateMappings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing scope mappings for MCP tools: [zeta_tool]");
    }

    @Test
    void allMissingMappingsAreReportedInDeterministicNameOrder() {
        ToolScopeRegistryValidator validator = validator(Set.of("inactive_tool"));

        assertThatThrownBy(validator::validateMappings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing scope mappings for MCP tools: [alpha_tool, zeta_tool]");
    }

    @Test
    void allExposedToolsWithPermissionMappingsPassStartupValidation() {
        ToolScopeRegistryValidator validator = validator(Set.of("alpha_tool", "zeta_tool"));

        assertThatCode(validator::validateMappings).doesNotThrowAnyException();
    }

    @Test
    void extraPermissionMappingsForInactiveToolsDoNotFailStartupValidation() {
        ToolScopeRegistryValidator validator = validator(Set.of("alpha_tool", "zeta_tool", "inactive_tool"));

        assertThatCode(validator::validateMappings).doesNotThrowAnyException();
    }

    private ToolScopeRegistryValidator validator(Set<String> mappedTools) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new ActiveTools())
                .build();
        ToolCallback[] callbacks = provider.getToolCallbacks();
        // Registration order must not determine the order of startup diagnostics.
        Arrays.sort(callbacks, Comparator.comparing(
                (ToolCallback callback) -> callback.getToolDefinition().name()).reversed());
        ToolAuthorizationService authorizationService = mock(ToolAuthorizationService.class);
        when(authorizationService.mappedToolNames()).thenReturn(mappedTools);
        return new ToolScopeRegistryValidator(() -> callbacks, authorizationService);
    }

    static class ActiveTools {

        @Tool(name = "zeta_tool", description = "An active test tool.")
        public String zeta() {
            return "zeta";
        }

        @Tool(name = "alpha_tool", description = "Another active test tool.")
        public String alpha() {
            return "alpha";
        }
    }
}
