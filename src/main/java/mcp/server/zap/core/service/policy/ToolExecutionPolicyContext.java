package mcp.server.zap.core.service.policy;

/**
 * Shared runtime policy hook input for tool execution checks.
 */
public record ToolExecutionPolicyContext(
        String toolName,
        String target,
        String correlationId
) {
}
