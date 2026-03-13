package mcp.server.zap.core.service.revocation;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();

    /**
     * Mark token as revoked until its expiration instant.
     */
    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        revokedTokens.put(tokenId, expiresAt);
        cleanupExpired();
    }

    /**
     * Revoke only when token is currently active and not previously revoked.
     */
    @Override
    public boolean revokeIfActive(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        while (true) {
            Instant existing = revokedTokens.putIfAbsent(tokenId, expiresAt);
            if (existing == null) {
                cleanupExpired();
                return true;
            }

            if (!existing.isAfter(now)) {
                if (revokedTokens.replace(tokenId, existing, expiresAt)) {
                    cleanupExpired();
                    return true;
                }
                continue;
            }
            return false;
        }
    }

    /**
     * Return true when token exists and has not naturally expired.
     */
    @Override
    public boolean isRevoked(String tokenId) {
        Instant expiresAt = revokedTokens.get(tokenId);
        if (expiresAt == null) {
            return false;
        }

        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) {
            revokedTokens.remove(tokenId, expiresAt);
            return false;
        }
        return true;
    }

    /**
     * Remove expired token revocations from in-memory map.
     */
    @Override
    public void cleanupExpired() {
        Instant now = Instant.now();
        revokedTokens.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    /**
     * Return number of currently tracked token revocations.
     */
    @Override
    public int size() {
        cleanupExpired();
        return revokedTokens.size();
    }

    /**
     * Clear all in-memory token revocations.
     */
    @Override
    public void clear() {
        revokedTokens.clear();
    }
}
