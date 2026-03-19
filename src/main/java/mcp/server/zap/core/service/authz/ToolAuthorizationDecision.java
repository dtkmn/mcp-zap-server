package mcp.server.zap.core.service.authz;

import java.util.List;

/**
 * Result of evaluating a tool authorization request.
 */
public record ToolAuthorizationDecision(
        boolean allowed,
        boolean mapped,
        String actionName,
        List<String> requiredScopes,
        List<String> grantedScopes,
        List<String> missingScopes
) {
}
