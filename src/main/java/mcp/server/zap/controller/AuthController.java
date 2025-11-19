package mcp.server.zap.controller;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ApiKeyProperties;
import mcp.server.zap.model.RefreshTokenRequest;
import mcp.server.zap.model.TokenRequest;
import mcp.server.zap.model.TokenResponse;
import mcp.server.zap.service.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for authentication and token management.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;
    private final ApiKeyProperties apiKeyProperties;

    public AuthController(JwtService jwtService, ApiKeyProperties apiKeyProperties) {
        this.jwtService = jwtService;
        this.apiKeyProperties = apiKeyProperties;
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
            @RequestBody(required = false) TokenRequest tokenRequest,
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
        
        log.info("Generated tokens for client: {}", client.getClientId());
        
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
    public ResponseEntity<TokenResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        
        if (request.getRefreshToken() == null || request.getRefreshToken().trim().isEmpty()) {
            log.warn("Refresh token request missing token");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            // Validate refresh token
            String clientId = jwtService.getClientIdFromToken(request.getRefreshToken());
            String tokenType = jwtService.getTokenType(request.getRefreshToken());
            
            if (!"refresh".equals(tokenType)) {
                log.warn("Invalid token type for refresh: {}", tokenType);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }
            
            // Find client to get scopes
            Optional<ApiKeyProperties.ApiKeyClient> clientOpt = apiKeyProperties.getApiKeys().stream()
                    .filter(client -> client.getClientId().equals(clientId))
                    .findFirst();
            
            if (clientOpt.isEmpty()) {
                log.warn("Client not found for refresh token: {}", clientId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            
            ApiKeyProperties.ApiKeyClient client = clientOpt.get();
            
            // Generate new access token
            String newAccessToken = jwtService.generateAccessToken(client.getClientId(), client.getScopes());
            
            log.info("Refreshed access token for client: {}", clientId);
            
            TokenResponse response = TokenResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(request.getRefreshToken())  // Keep same refresh token
                    .tokenType("Bearer")
                    .expiresIn(jwtService.getSecondsUntilExpiration(newAccessToken))
                    .clientId(client.getClientId())
                    .scopes(client.getScopes())
                    .build();
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error refreshing token: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
            String clientId = jwtService.getClientIdFromToken(token);
            List<String> scopes = jwtService.getScopesFromToken(token);
            long expiresIn = jwtService.getSecondsUntilExpiration(token);
            
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "clientId", clientId,
                    "scopes", scopes,
                    "expiresIn", expiresIn
            ));
            
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "error", e.getMessage()
            ));
        }
    }
}
