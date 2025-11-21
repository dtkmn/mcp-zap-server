package mcp.server.zap.service;

import mcp.server.zap.configuration.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-key-for-jwt-at-least-32-characters-long-required");
        jwtProperties.setIssuer("test-issuer");
        jwtProperties.setAccessTokenExpiry(3600); // 1 hour
        jwtProperties.setRefreshTokenExpiry(604800); // 7 days
        jwtProperties.setEnabled(true);

        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void testGenerateAccessToken() {
        // Given
        String clientId = "test-client";
        List<String> scopes = Arrays.asList("scan:read", "scan:write");

        // When
        String token = jwtService.generateAccessToken(clientId, scopes);

        // Then
        assertThat(token).isNotNull().isNotEmpty();
        assertThat(jwtService.getClientIdFromToken(token)).isEqualTo(clientId);
        assertThat(jwtService.getScopesFromToken(token)).containsExactlyInAnyOrderElementsOf(scopes);
        assertThat(jwtService.getTokenType(token)).isEqualTo("access");
    }

    @Test
    void testGenerateRefreshToken() {
        // Given
        String clientId = "test-client";

        // When
        String token = jwtService.generateRefreshToken(clientId);

        // Then
        assertThat(token).isNotNull().isNotEmpty();
        assertThat(jwtService.getClientIdFromToken(token)).isEqualTo(clientId);
        assertThat(jwtService.getTokenType(token)).isEqualTo("refresh");
    }

    @Test
    void testValidateValidToken() {
        // Given
        String clientId = "test-client";
        String token = jwtService.generateAccessToken(clientId, Arrays.asList("scan:read"));

        // When/Then - Should not throw exception
        jwtService.validateToken(token);
    }

    @Test
    void testValidateInvalidToken() {
        // Given
        String invalidToken = "invalid.jwt.token";

        // When/Then
        assertThatThrownBy(() -> jwtService.validateToken(invalidToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void testValidateTamperedToken() {
        // Given
        String token = jwtService.generateAccessToken("test-client", Arrays.asList("scan:read"));
        String tamperedToken = token.substring(0, token.length() - 5) + "12345";

        // When/Then
        assertThatThrownBy(() -> jwtService.validateToken(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void testGetTokenId() {
        // Given
        String token = jwtService.generateAccessToken("test-client", Arrays.asList("scan:read"));

        // When
        String tokenId = jwtService.getTokenId(token);

        // Then
        assertThat(tokenId).isNotNull().isNotEmpty();
    }

    @Test
    void testTokenIdIsUnique() {
        // Given
        String token1 = jwtService.generateAccessToken("test-client", Arrays.asList("scan:read"));
        String token2 = jwtService.generateAccessToken("test-client", Arrays.asList("scan:read"));

        // When
        String tokenId1 = jwtService.getTokenId(token1);
        String tokenId2 = jwtService.getTokenId(token2);

        // Then
        assertThat(tokenId1).isNotEqualTo(tokenId2);
    }

    @Test
    void testExpiredToken() throws InterruptedException {
        // Given - Create a JWT service with very short expiry
        JwtProperties shortExpiryProps = new JwtProperties();
        shortExpiryProps.setSecret("test-secret-key-for-jwt-at-least-32-characters-long-required");
        shortExpiryProps.setIssuer("test-issuer");
        shortExpiryProps.setAccessTokenExpiry(1); // 1 second
        shortExpiryProps.setEnabled(true);

        JwtService shortExpiryService = new JwtService(shortExpiryProps);
        String token = shortExpiryService.generateAccessToken("test-client", Arrays.asList("scan:read"));

        // Token should not be expired immediately
        assertThat(shortExpiryService.isTokenExpired(token)).isFalse();
        
        // Wait for token to expire
        Thread.sleep(2000);

        // Token should now be expired
        assertThat(shortExpiryService.isTokenExpired(token)).isTrue();
        assertThat(shortExpiryService.getSecondsUntilExpiration(token)).isEqualTo(0);
    }

    @Test
    void testInsufficientSecretKeyLength() {
        // Given
        JwtProperties invalidProps = new JwtProperties();
        invalidProps.setSecret("short-secret"); // Too short
        invalidProps.setIssuer("test-issuer");

        // When/Then
        assertThatThrownBy(() -> new JwtService(invalidProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 256 bits");
    }

    @Test
    void testMissingSecretKey() {
        // Given
        JwtProperties invalidProps = new JwtProperties();
        invalidProps.setSecret(null);
        invalidProps.setIssuer("test-issuer");

        // When/Then
        assertThatThrownBy(() -> new JwtService(invalidProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void testEmptySecretKey() {
        // Given
        JwtProperties invalidProps = new JwtProperties();
        invalidProps.setSecret("");
        invalidProps.setIssuer("test-issuer");

        // When/Then
        assertThatThrownBy(() -> new JwtService(invalidProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void testGetScopesFromTokenWithNoScopes() {
        // Given
        String token = jwtService.generateRefreshToken("test-client");

        // When
        List<String> scopes = jwtService.getScopesFromToken(token);

        // Then
        assertThat(scopes).isEmpty();
    }
}
