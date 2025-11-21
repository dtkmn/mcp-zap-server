package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for JWT token generation, validation, and parsing.
 */
@Slf4j
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        
        // Initialize secret key
        if (jwtProperties.getSecret() == null || jwtProperties.getSecret().trim().isEmpty()) {
            throw new IllegalStateException("JWT secret is not configured. Please set mcp.server.auth.jwt.secret");
        }
        
        // Ensure secret is at least 256 bits
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret must be at least 256 bits (32 characters)");
        }
        
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        
        // Initialize JWT encoder and decoder
        this.jwtEncoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(secretKey));
        this.jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        
        log.info("JWT service initialized with issuer: {}", jwtProperties.getIssuer());
    }

    /**
     * Generate an access token for a client.
     *
     * @param clientId Client identifier
     * @param scopes List of scopes/permissions
     * @return JWT access token
     */
    public String generateAccessToken(String clientId, List<String> scopes) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.getAccessTokenExpiry());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(clientId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("scopes", scopes)
                .claim("type", "access")
                .build();

        JwtEncoderParameters parameters = JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        );

        return jwtEncoder.encode(parameters).getTokenValue();
    }

    /**
     * Generate a refresh token for a client.
     *
     * @param clientId Client identifier
     * @return JWT refresh token
     */
    public String generateRefreshToken(String clientId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(jwtProperties.getRefreshTokenExpiry());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(clientId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .build();

        JwtEncoderParameters parameters = JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        );

        return jwtEncoder.encode(parameters).getTokenValue();
    }

    /**
     * Validate and parse a JWT token.
     *
     * @param token JWT token string
     * @return Parsed JWT
     * @throws JwtException if token is invalid
     */
    public Jwt validateToken(String token) {
        try {
            return jwtDecoder.decode(token);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Extract client ID from token.
     *
     * @param token JWT token
     * @return Client ID
     */
    public String getClientIdFromToken(String token) {
        Jwt jwt = validateToken(token);
        return jwt.getSubject();
    }

    /**
     * Extract scopes from token.
     *
     * @param token JWT token
     * @return List of scopes
     */
    @SuppressWarnings("unchecked")
    public List<String> getScopesFromToken(String token) {
        Jwt jwt = validateToken(token);
        Object scopesClaim = jwt.getClaim("scopes");
        if (scopesClaim instanceof List) {
            return (List<String>) scopesClaim;
        }
        return List.of();
    }

    /**
     * Get token type (access or refresh).
     *
     * @param token JWT token
     * @return Token type
     */
    public String getTokenType(String token) {
        Jwt jwt = validateToken(token);
        return jwt.getClaim("type");
    }

    /**
     * Get token ID (jti claim) for blacklist tracking.
     *
     * @param token JWT token
     * @return Token ID
     */
    public String getTokenId(String token) {
        Jwt jwt = validateToken(token);
        return jwt.getId();
    }

    /**
     * Check if token is expired.
     *
     * @param token JWT token
     * @return true if expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Jwt jwt = validateToken(token);
            return jwt.getExpiresAt() != null && jwt.getExpiresAt().isBefore(Instant.now());
        } catch (JwtException e) {
            return true;
        }
    }

    /**
     * Get seconds until token expiration.
     *
     * @param token JWT token
     * @return Seconds until expiration, or 0 if expired
     */
    public long getSecondsUntilExpiration(String token) {
        try {
            Jwt jwt = validateToken(token);
            Instant expiry = jwt.getExpiresAt();
            if (expiry == null) {
                return 0;
            }
            Instant now = Instant.now();
            long seconds = expiry.getEpochSecond() - now.getEpochSecond();
            return Math.max(0, seconds);
        } catch (JwtException e) {
            return 0;
        }
    }
}
