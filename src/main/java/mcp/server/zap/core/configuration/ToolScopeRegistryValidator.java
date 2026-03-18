package mcp.server.zap.core.configuration;

import jakarta.annotation.PostConstruct;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fails fast when a public tool is exposed without an authorization mapping.
 */
@Component
public class ToolScopeRegistryValidator {
    private final ToolCallbackProvider toolCallbackProvider;
    private final ToolAuthorizationService toolAuthorizationService;

    public ToolScopeRegistryValidator(ToolCallbackProvider toolCallbackProvider,
                                      ToolAuthorizationService toolAuthorizationService) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.toolAuthorizationService = toolAuthorizationService;
    }

    @PostConstruct
    void validateMappings() {
        Set<String> mappedTools = toolAuthorizationService.mappedToolNames();
        Set<String> missingMappings = new TreeSet<>();

        for (ToolCallback callback : toolCallbackProvider.getToolCallbacks()) {
            String toolName = callback.getToolDefinition().name();
            if (!mappedTools.contains(toolName)) {
                missingMappings.add(toolName);
            }
        }

        if (!missingMappings.isEmpty()) {
            throw new IllegalStateException(
                    "Missing scope mappings for MCP tools: " + Arrays.toString(missingMappings.toArray()));
        }
    }
}
