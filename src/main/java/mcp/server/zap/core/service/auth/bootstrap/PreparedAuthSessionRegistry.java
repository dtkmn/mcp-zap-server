package mcp.server.zap.core.service.auth.bootstrap;

import java.util.Optional;

/**
 * Stores prepared auth sessions between prepare and validate steps.
 */
public interface PreparedAuthSessionRegistry {

    PreparedAuthSession save(PreparedAuthSession session);

    Optional<PreparedAuthSession> findById(String sessionId);
}
