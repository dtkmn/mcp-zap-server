package mcp.server.zap.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.JwtProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Service for JWT token generation, validation, and parsing.
 */
@Slf4j
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;
    private final JwtParser jwtParser;

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
        
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        
        // Initialize JWT parser
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
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

        return Jwts.builder()
                .header()
                    .type("JWT")
                .and()
                .subject(clientId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .claim("scopes", scopes)
                .claim("type", "access")
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
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

        return Jwts.builder()
                .header()
                    .type("JWT")
                .and()
                .subject(clientId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validate and parse a JWT token.
     *
     * @param token JWT token string
     * @return Parsed claims
     * @throws JwtException if token is invalid
     */
    public Claims validateToken(String token) {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("JWT token expired: {}", e.getMessage());
            throw e;
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT token: {}", e.getMessage());
            throw e;
        } catch (MalformedJwtException e) {
            log.warn("Malformed JWT token: {}", e.getMessage());
            throw e;
        } catch (SecurityException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("JWT claims string is empty: {}", e.getMessage());
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
        Claims claims = validateToken(token);
        return claims.getSubject();
    }

    /**
     * Extract scopes from token.
     *
     * @param token JWT token
     * @return List of scopes
     */
    @SuppressWarnings("unchecked")
    public List<String> getScopesFromToken(String token) {
        Claims claims = validateToken(token);
        Object scopesClaim = claims.get("scopes");
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
        Claims claims = validateToken(token);
        return claims.get("type", String.class);
    }

    /**
     * Get token ID (jti claim) for blacklist tracking.
     *
     * @param token JWT token
     * @return Token ID
     */
    public String getTokenId(String token) {
        Claims claims = validateToken(token);
        return claims.getId();
    }

    /**
     * Check if token is expired.
     *
     * @param token JWT token
     * @return true if expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = validateToken(token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
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
            Claims claims = validateToken(token);
            Instant expiry = claims.getExpiration().toInstant();
            Instant now = Instant.now();
            long seconds = expiry.getEpochSecond() - now.getEpochSecond();
            return Math.max(0, seconds);
        } catch (ExpiredJwtException e) {
            return 0;
        }
    }
}
