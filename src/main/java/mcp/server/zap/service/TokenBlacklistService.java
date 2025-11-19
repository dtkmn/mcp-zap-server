package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing blacklisted tokens.
 * Provides in-memory storage for revoked tokens.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    // Store token ID -> expiration time
    private final Map<String, Instant> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Add a token to the blacklist.
     *
     * @param tokenId Token ID (jti claim)
     * @param expirationTime When the token expires naturally
     */
    public void blacklistToken(String tokenId, Instant expirationTime) {
        blacklistedTokens.put(tokenId, expirationTime);
        log.info("Token {} added to blacklist", tokenId);
        
        // Clean up expired entries
        cleanupExpiredTokens();
    }

    /**
     * Check if a token is blacklisted.
     *
     * @param tokenId Token ID (jti claim)
     * @return true if blacklisted
     */
    public boolean isBlacklisted(String tokenId) {
        // Clean up first
        cleanupExpiredTokens();
        
        return blacklistedTokens.containsKey(tokenId);
    }

    /**
     * Remove expired tokens from blacklist.
     * Only keeps tokens that haven't expired yet.
     */
    public void cleanupExpiredTokens() {
        Instant now = Instant.now();
        blacklistedTokens.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    /**
     * Get current blacklist size.
     *
     * @return Number of blacklisted tokens
     */
    public int size() {
        cleanupExpiredTokens();
        return blacklistedTokens.size();
    }

    /**
     * Clear all blacklisted tokens (for testing).
     */
    public void clear() {
        blacklistedTokens.clear();
        log.info("Token blacklist cleared");
    }
}
