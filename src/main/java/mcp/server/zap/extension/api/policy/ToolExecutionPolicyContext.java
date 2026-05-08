package mcp.server.zap.extension.api.policy;

/**
 * Extension API input for tool execution policy checks.
 */
public record ToolExecutionPolicyContext(
        String toolName,
        String target,
        String correlationId
) {
}
