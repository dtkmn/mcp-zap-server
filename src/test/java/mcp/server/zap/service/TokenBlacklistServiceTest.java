package mcp.server.zap.service;

import mcp.server.zap.configuration.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBlacklistServiceTest {

    private TokenBlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        blacklistService = new TokenBlacklistService();
    }

    @Test
    void testBlacklistToken() {
        // Given
        String tokenId = UUID.randomUUID().toString();
        Instant expiration = Instant.now().plusSeconds(3600);

        // When
        blacklistService.blacklistToken(tokenId, expiration);

        // Then
        assertThat(blacklistService.isBlacklisted(tokenId)).isTrue();
    }

    @Test
    void testIsBlacklistedReturnsFalseForNonBlacklistedToken() {
        // Given
        String tokenId = UUID.randomUUID().toString();

        // When/Then
        assertThat(blacklistService.isBlacklisted(tokenId)).isFalse();
    }

    @Test
    void testCleanupExpiredTokens() {
        // Given
        String expiredTokenId = UUID.randomUUID().toString();
        String validTokenId = UUID.randomUUID().toString();

        Instant pastExpiration = Instant.now().minusSeconds(3600);
        Instant futureExpiration = Instant.now().plusSeconds(3600);

        blacklistService.blacklistToken(expiredTokenId, pastExpiration);
        blacklistService.blacklistToken(validTokenId, futureExpiration);

        // When
        blacklistService.cleanupExpiredTokens();

        // Then
        assertThat(blacklistService.isBlacklisted(expiredTokenId)).isFalse();
        assertThat(blacklistService.isBlacklisted(validTokenId)).isTrue();
    }

    @Test
    void testBlacklistSameTokenTwice() {
        // Given
        String tokenId = UUID.randomUUID().toString();
        Instant expiration1 = Instant.now().plusSeconds(3600);
        Instant expiration2 = Instant.now().plusSeconds(7200);

        // When
        blacklistService.blacklistToken(tokenId, expiration1);
        blacklistService.blacklistToken(tokenId, expiration2);

        // Then
        assertThat(blacklistService.isBlacklisted(tokenId)).isTrue();
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Given
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        // When - Blacklist tokens from multiple threads
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                String tokenId = "token-" + index;
                Instant expiration = Instant.now().plusSeconds(3600);
                blacklistService.blacklistToken(tokenId, expiration);
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - All tokens should be blacklisted
        for (int i = 0; i < threadCount; i++) {
            assertThat(blacklistService.isBlacklisted("token-" + i)).isTrue();
        }
    }
}
