package mcp.server.zap.core.service.auth.bootstrap;

import mcp.server.zap.core.gateway.TargetDescriptor;

/**
 * In-memory descriptor for a prepared auth session.
 */
public record PreparedAuthSession(
        String sessionId,
        String profileId,
        AuthBootstrapKind authKind,
        String providerId,
        TargetDescriptor target,
        HttpOrigin authorizedOrigin,
        String credentialReference,
        String contextName,
        String contextId,
        String zapUserName,
        String userId,
        String headerName,
        String loginUrl,
        boolean engineBound
) {
}
