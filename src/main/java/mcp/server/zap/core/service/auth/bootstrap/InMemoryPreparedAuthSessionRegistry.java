package mcp.server.zap.core.service.auth.bootstrap;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Default session registry for the guided auth bootstrap flow.
 */
@Component
public class InMemoryPreparedAuthSessionRegistry implements PreparedAuthSessionRegistry {
    private final ConcurrentMap<String, PreparedAuthSession> sessions = new ConcurrentHashMap<>();

    @Override
    public PreparedAuthSession save(PreparedAuthSession session) {
        sessions.put(session.sessionId(), session);
        return session;
    }

    @Override
    public Optional<PreparedAuthSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }
}
