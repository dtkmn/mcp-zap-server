package mcp.server.zap.core.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.model.RefreshTokenRequest;
import mcp.server.zap.core.model.RevokeTokenRequest;
import mcp.server.zap.core.model.TokenRequest;
import mcp.server.zap.core.model.TokenResponse;
import mcp.server.zap.core.service.TokenBlacklistService;
import mcp.server.zap.core.service.JwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for authentication and token management.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(prefix = "mcp.server.auth.jwt", name = "enabled", havingValue = "true")
public class AuthController {

    private final JwtService jwtService;
    private final ApiKeyProperties apiKeyProperties;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(JwtService jwtService,
                          ApiKeyProperties apiKeyProperties,
                          TokenBlacklistService tokenBlacklistService) {
        this.jwtService = jwtService;
        this.apiKeyProperties = apiKeyProperties;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * Exchange API key for JWT tokens.
     *
     * POST /auth/token
     * Body: { "apiKey": "your-api-key" }
     * Or Header: X-API-Key: your-api-key
     */
    @PostMapping("/token")
    public ResponseEntity<TokenResponse> generateToken(
            @Valid @RequestBody(required = false) TokenRequest tokenRequest,
            @RequestHeader(value = "X-API-Key", required = false) String apiKeyHeader) {
        
        // Get API key from body or header
        String apiKey = tokenRequest != null ? tokenRequest.getApiKey() : apiKeyHeader;
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Token request missing API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .build();
        }

        // Find matching client
        Optional<ApiKeyProperties.ApiKeyClient> clientOpt = apiKeyProperties.getApiKeys().stream()
                .filter(client -> client.getKey().equals(apiKey))
                .findFirst();

        if (clientOpt.isEmpty()) {
            log.warn("Invalid API key provided for token generation");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .build();
        }

        ApiKeyProperties.ApiKeyClient client = clientOpt.get();
        
        // Generate tokens
        String accessToken = jwtService.generateAccessToken(client.getClientId(), client.getScopes());
        String refreshToken = jwtService.generateRefreshToken(client.getClientId());
        
        log.info("Generated token pair");
        
        TokenResponse response = TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getSecondsUntilExpiration(accessToken))
                .clientId(client.getClientId())
                .scopes(client.getScopes())
                .build();
        
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh access token using refresh token.
     *
     * POST /auth/refresh
     * Body: { "refreshToken": "your-refresh-token" }
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            // Validate refresh token
            String refreshToken = request.getRefreshToken();
            Jwt jwt = jwtService.validateToken(refreshToken);
            String clientId = jwt.getSubject();
            String tokenType = jwt.getClaimAsString("type");
            String tokenId = jwt.getId();
            Instant expiresAt = jwt.getExpiresAt() != null
                    ? jwt.getExpiresAt()
                    : Instant.now().plusSeconds(60);

            if (!"refresh".equals(tokenType)) {
                log.warn("Invalid token type for refresh");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }

            if (tokenId == null || tokenId.trim().isEmpty()) {
                log.warn("Refresh token is missing jti claim");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            
            // Find client to get scopes
            Optional<ApiKeyProperties.ApiKeyClient> clientOpt = apiKeyProperties.getApiKeys().stream()
                    .filter(client -> client.getClientId().equals(clientId))
                    .findFirst();
            
            if (clientOpt.isEmpty()) {
                log.warn("Client not found for refresh token");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            
            ApiKeyProperties.ApiKeyClient client = clientOpt.get();

            // Refresh token is one-time-use: consume current token before issuing the next one.
            if (!tokenBlacklistService.consumeTokenForOneTimeUse(tokenId, expiresAt)) {
                log.warn("Refresh token replay detected");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            
            // Rotation: issue a new token pair.
            String newAccessToken = jwtService.generateAccessToken(client.getClientId(), client.getScopes());
            String newRefreshToken = jwtService.generateRefreshToken(client.getClientId());
            
            log.info("Refreshed access token");
            
            TokenResponse response = TokenResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .tokenType("Bearer")
                    .expiresIn(jwtService.getSecondsUntilExpiration(newAccessToken))
                    .clientId(client.getClientId())
                    .scopes(client.getScopes())
                    .build();
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error refreshing token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    /**
     * Revoke an access or refresh token by adding its jti to the blacklist.
     *
     * POST /auth/revoke
     * Body: { "token": "your-jwt-token" }
     */
    @PostMapping("/revoke")
    public ResponseEntity<Object> revokeToken(@Valid @RequestBody RevokeTokenRequest request) {
        try {
            Jwt jwt = jwtService.validateToken(request.getToken());
            String tokenId = jwt.getId();
            if (tokenId == null || tokenId.trim().isEmpty()) {
                log.warn("Cannot revoke token without jti claim");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("revoked", false, "error", "Token is missing jti claim"));
            }

            Instant expiresAt = jwt.getExpiresAt() != null
                    ? jwt.getExpiresAt()
                    : Instant.now().plusSeconds(60);
            tokenBlacklistService.blacklistToken(tokenId, expiresAt);

            log.info("Revoked token");
            return ResponseEntity.ok(Map.of(
                    "revoked", true,
                    "tokenId", tokenId,
                    "expiresAt", expiresAt.toString()
            ));
        } catch (JwtException e) {
            log.warn("Invalid token provided for revocation");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("revoked", false, "error", "Invalid or expired JWT token"));
        }
    }

    /**
     * Validate a token (for debugging).
     *
     * GET /auth/validate
     * Header: Authorization: Bearer your-token
     */
    @GetMapping("/validate")
    public ResponseEntity<Object> validateToken(@RequestHeader("Authorization") String authHeader) {
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("valid", false, "error", "Missing or invalid Authorization header"));
        }

        String token = authHeader.substring(7);
        
        try {
            String tokenId = jwtService.getTokenId(token);
            if (tokenId != null && tokenBlacklistService.isBlacklisted(tokenId)) {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "error", "Token has been revoked"
                ));
            }

            String clientId = jwtService.getClientIdFromToken(token);
            List<String> scopes = jwtService.getScopesFromToken(token);
            long expiresIn = jwtService.getSecondsUntilExpiration(token);
            
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "tokenId", tokenId,
                    "clientId", clientId,
                    "scopes", scopes,
                    "expiresIn", expiresIn
            ));
            
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "error", "Invalid or expired JWT token"
            ));
        }
    }
}
