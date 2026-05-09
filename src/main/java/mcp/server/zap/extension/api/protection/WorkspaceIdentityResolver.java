package mcp.server.zap.extension.api.protection;

/**
 * Extension API contract for resolving current requester workspace identity.
 */
public interface WorkspaceIdentityResolver {

    String resolveCurrentWorkspaceId();

    String resolveWorkspaceId(String clientId);
}
