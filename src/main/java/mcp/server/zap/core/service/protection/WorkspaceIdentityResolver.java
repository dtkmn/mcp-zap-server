package mcp.server.zap.core.service.protection;

/**
 * Core contract for resolving the current requester workspace and requester-to-workspace mapping.
 */
public interface WorkspaceIdentityResolver {

    String resolveCurrentWorkspaceId();

    String resolveWorkspaceId(String clientId);
}
