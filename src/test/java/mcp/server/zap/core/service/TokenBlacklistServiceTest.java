package mcp.server.zap.core.service;

import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBlacklistServiceTest {

    private TokenBlacklistService blacklistService;

    @BeforeEach
    void setUp() {
        blacklistService = new TokenBlacklistService(new InMemoryTokenRevocationStore());
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
    void testBlankTokenIdIsTreatedAsBlacklisted() {
        assertThat(blacklistService.isBlacklisted("")).isTrue();
        assertThat(blacklistService.isBlacklisted("   ")).isTrue();
        assertThat(blacklistService.isBlacklisted(null)).isTrue();
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
    void testConsumeTokenForOneTimeUseRejectsReplay() {
        String tokenId = UUID.randomUUID().toString();
        Instant expiration = Instant.now().plusSeconds(3600);

        boolean firstUse = blacklistService.consumeTokenForOneTimeUse(tokenId, expiration);
        boolean secondUse = blacklistService.consumeTokenForOneTimeUse(tokenId, expiration);

        assertThat(firstUse).isTrue();
        assertThat(secondUse).isFalse();
    }

    @Test
    void testConsumeTokenForOneTimeUseWithBlankTokenId() {
        Instant expiration = Instant.now().plusSeconds(3600);
        assertThat(blacklistService.consumeTokenForOneTimeUse("", expiration)).isFalse();
        assertThat(blacklistService.consumeTokenForOneTimeUse("   ", expiration)).isFalse();
        assertThat(blacklistService.consumeTokenForOneTimeUse(null, expiration)).isFalse();
    }

}
