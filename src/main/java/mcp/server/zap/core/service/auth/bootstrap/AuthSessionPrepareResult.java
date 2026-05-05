package mcp.server.zap.core.service.auth.bootstrap;

import java.util.List;

/**
 * Result of preparing an auth bootstrap session.
 */
public record AuthSessionPrepareResult(
        PreparedAuthSession session,
        List<String> warnings
) {
}
