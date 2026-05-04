package mcp.server.zap.core.service.auth.bootstrap;

import mcp.server.zap.core.gateway.TargetDescriptor;

/**
 * In-memory descriptor for a prepared auth session.
 */
public record PreparedAuthSession(
        String sessionId,
        String sessionLabel,
        AuthBootstrapKind authKind,
        String providerId,
        TargetDescriptor target,
        String credentialReference,
        String contextName,
        String contextId,
        String userName,
        String userId,
        String headerName,
        String loginUrl,
        boolean engineBound
) {
}
