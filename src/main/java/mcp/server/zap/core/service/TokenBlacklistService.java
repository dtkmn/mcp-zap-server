package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.service.revocation.TokenRevocationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Service for managing blacklisted tokens.
 * Uses a pluggable revocation store for local/dev and shared/prod backends.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "mcp.server.auth.jwt", name = "enabled", havingValue = "true")
public class TokenBlacklistService {

    private final TokenRevocationStore tokenRevocationStore;

    public TokenBlacklistService(TokenRevocationStore tokenRevocationStore) {
        this.tokenRevocationStore = tokenRevocationStore;
    }

    /**
     * Add a token to the blacklist.
     *
     * @param tokenId Token ID (jti claim)
     * @param expirationTime When the token expires naturally
     */
    public void blacklistToken(String tokenId, Instant expirationTime) {
        if (tokenId == null || tokenId.isBlank()) {
            log.warn("Skipping blacklist operation for blank token ID");
            return;
        }
        tokenRevocationStore.revoke(tokenId, expirationTime);
        log.info("Token {} added to blacklist", tokenId);
    }

    /**
     * Consume a token for one-time usage semantics.
     *
     * @return true when this call marks token as consumed for the first time,
     * false when token was already consumed/revoked.
     */
    public boolean consumeTokenForOneTimeUse(String tokenId, Instant expirationTime) {
        if (tokenId == null || tokenId.isBlank()) {
            log.warn("Cannot consume blank token ID");
            return false;
        }
        boolean consumed = tokenRevocationStore.revokeIfActive(tokenId, expirationTime);
        if (!consumed) {
            log.warn("Detected replay/already-revoked token usage: {}", tokenId);
        }
        return consumed;
    }

    /**
     * Check if a token is blacklisted.
     *
     * @param tokenId Token ID (jti claim)
     * @return true if blacklisted
     */
    public boolean isBlacklisted(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            log.warn("Blank token ID treated as revoked");
            return true;
        }
        return tokenRevocationStore.isRevoked(tokenId);
    }

    /**
     * Remove expired tokens from blacklist.
     * Only keeps tokens that haven't expired yet.
     */
    public void cleanupExpiredTokens() {
        tokenRevocationStore.cleanupExpired();
    }

    /**
     * Get current blacklist size.
     *
     * @return Number of blacklisted tokens
     */
    public int size() {
        return tokenRevocationStore.size();
    }

    /**
     * Clear all blacklisted tokens (for testing).
     */
    public void clear() {
        tokenRevocationStore.clear();
        log.info("Token blacklist cleared");
    }
}
