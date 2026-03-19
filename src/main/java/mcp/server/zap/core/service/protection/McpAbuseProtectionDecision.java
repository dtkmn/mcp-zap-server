package mcp.server.zap.core.service.protection;

/**
 * Result of rate-limit / quota / backpressure evaluation for an MCP request.
 */
public record McpAbuseProtectionDecision(
        boolean allowed,
        String errorCode,
        String reason,
        String toolName,
        String clientId,
        String workspaceId,
        long retryAfterSeconds
) {
    public static McpAbuseProtectionDecision allow(String toolName, String clientId, String workspaceId) {
        return new McpAbuseProtectionDecision(true, null, null, toolName, clientId, workspaceId, 0L);
    }

    public static McpAbuseProtectionDecision reject(String errorCode,
                                                    String reason,
                                                    String toolName,
                                                    String clientId,
                                                    String workspaceId,
                                                    long retryAfterSeconds) {
        return new McpAbuseProtectionDecision(
                false,
                errorCode,
                reason,
                toolName,
                clientId,
                workspaceId,
                retryAfterSeconds
        );
    }
}
