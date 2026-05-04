package mcp.server.zap.core.service.auth.bootstrap;

import java.util.List;

/**
 * Result of validating a prepared auth session.
 */
public record AuthSessionValidationResult(
        PreparedAuthSession session,
        boolean valid,
        String outcome,
        List<String> diagnostics
) {
}
