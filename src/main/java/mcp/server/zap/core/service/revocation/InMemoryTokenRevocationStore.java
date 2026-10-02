package mcp.server.zap.core.service.revocation;

import mcp.server.zap.core.service.JwtTokenLifetime;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTokenRevocationStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTokenRevocationStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Retain the raw expiry claim through the full JWT validation lifetime.
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
        Instant cutoff = JwtTokenLifetime.revocationCutoff(clock.instant());
        if (expiresAt.isBefore(cutoff)) {
            return false;
        }
        while (true) {
            Instant existing = revokedTokens.putIfAbsent(tokenId, expiresAt);
            if (existing == null) {
                break;
            }

            if (existing.isBefore(cutoff)) {
                if (revokedTokens.replace(tokenId, existing, expiresAt)) {
                    break;
                }
                continue;
            }
            return false;
        }
        cleanupExpired();
        return !expiresAt.isBefore(JwtTokenLifetime.revocationCutoff(clock.instant()));
    }

    /**
     * Return true while the JWT validator can still accept the recorded token.
     */
    @Override
    public boolean isRevoked(String tokenId) {
        Instant expiresAt = revokedTokens.get(tokenId);
        if (expiresAt == null) {
            return false;
        }

        Instant cutoff = JwtTokenLifetime.revocationCutoff(clock.instant());
        if (expiresAt.isBefore(cutoff)) {
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
        Instant cutoff = JwtTokenLifetime.revocationCutoff(clock.instant());
        revokedTokens.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
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
