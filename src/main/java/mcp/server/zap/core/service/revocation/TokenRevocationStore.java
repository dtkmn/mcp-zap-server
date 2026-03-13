package mcp.server.zap.core.service.revocation;

import java.time.Instant;

/**
 * Persistence abstraction for revoked JWT token IDs (jti).
 */
public interface TokenRevocationStore {

    void revoke(String tokenId, Instant expiresAt);

    /**
     * Atomically revokes token ID only if it is not already actively revoked.
     *
     * @return true when this call consumed/revoked the token for first use,
     * false when token was already revoked and still active.
     */
    boolean revokeIfActive(String tokenId, Instant expiresAt);

    boolean isRevoked(String tokenId);

    void cleanupExpired();

    int size();

    void clear();
}
