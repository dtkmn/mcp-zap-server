package mcp.server.zap.configuration;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.service.JwtService;
import mcp.server.zap.service.TokenBlacklistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Security configuration for the MCP ZAP Server.
 * Supports three authentication modes:
 * - NONE: No authentication (development/testing only) - CSRF enabled
 * - API_KEY: Simple API key authentication - CSRF enabled
 * - JWT: JWT token-based authentication (recommended for production) - CSRF enabled
 * 
 * IMPORTANT: CSRF protection is ALWAYS enabled (Spring Security default) across all modes.
 * This follows security best practices and provides defense-in-depth protection against
 * Cross-Site Request Forgery attacks.
 * 
 * Note: This API is designed for machine-to-machine communication (MCP clients). If you
 * experience CSRF token issues with non-browser clients, ensure your client includes the
 * CSRF token in requests, or document why CSRF can be safely ignored for your use case.
 * For local STDIO-based MCP clients, use NONE mode with proper network isolation.
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    public enum SecurityMode {
        NONE,      // No authentication
        API_KEY,   // API key authentication only
        JWT        // JWT authentication (with API key fallback)
    }

    @Value("${mcp.server.security.mode:api-key}")
    private String securityModeConfig;

    @Value("${mcp.server.apiKey:}")
    private String legacyMcpApiKey;

    @Value("${mcp.server.security.enabled:true}")
    private boolean securityEnabled;

    private final JwtProperties jwtProperties;
    private final ApiKeyProperties apiKeyProperties;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    public SecurityConfig(JwtProperties jwtProperties,
                         ApiKeyProperties apiKeyProperties,
                         JwtService jwtService,
                         TokenBlacklistService tokenBlacklistService) {
        this.jwtProperties = jwtProperties;
        this.apiKeyProperties = apiKeyProperties;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /**
     * Configure security filter chain based on the selected security mode.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        SecurityMode mode = getSecurityMode();
        
        // If authentication is disabled or mode is NONE, permit all requests
        // CSRF protection disabled for /mcp endpoint (used by mcpo for MCP protocol)
        if (!securityEnabled || mode == SecurityMode.NONE) {
            log.warn("⚠️ SECURITY DISABLED - All requests will be permitted without authentication");
            log.warn("   This should ONLY be used in development/testing environments");
            log.warn("   CSRF protection: DISABLED for /mcp endpoint (MCP protocol)");
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
        }

        // Apply authentication for API_KEY or JWT mode
        // CSRF protection is ENABLED by default (Spring Security default behavior)
        log.info("Security mode: {} - Authentication required for all endpoints (except public paths)", mode);
        log.info("CSRF protection: ENABLED (Spring Security default)");
        http
            // CSRF protection NOT disabled - using Spring Security default
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/info", "/auth/token", "/auth/refresh").permitAll()
                .anyExchange().authenticated()
            )
            .addFilterAt(authenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }
    
    /**
     * Get the configured security mode.
     */
    private SecurityMode getSecurityMode() {
        try {
            return SecurityMode.valueOf(securityModeConfig.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid security mode '{}', defaulting to API_KEY", securityModeConfig);
            return SecurityMode.API_KEY;
        }
    }

    /**
     * Authentication filter that validates JWT tokens or API keys based on security mode.
     */
    @Bean
    public WebFilter authenticationFilter() {
        return (exchange, chain) -> {
            SecurityMode mode = getSecurityMode();
            
            // Skip authentication if security is disabled or mode is NONE
            if (!securityEnabled || mode == SecurityMode.NONE) {
                return chain.filter(exchange);
            }

            // Skip authentication for public endpoints
            String path = exchange.getRequest().getPath().value();
            if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")
                || path.startsWith("/auth/token") || path.startsWith("/auth/refresh")) {
                return chain.filter(exchange);
            }

            // Authentication based on security mode
            return switch (mode) {
                case JWT -> authenticateWithJwtFirst(exchange, chain);
                case API_KEY -> authenticateWithApiKeyOnly(exchange, chain);
                default -> chain.filter(exchange); // Should not reach here
            };
        };
    }
    
    /**
     * JWT mode: Try JWT first, fall back to API key for backward compatibility.
     */
    private Mono<Void> authenticateWithJwtFirst(ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return authenticateWithJwt(token, exchange, chain);
        }

        // Fall back to API key authentication
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return authenticateWithApiKey(apiKey, exchange, chain);
        }

        log.warn("Missing authentication in request to {} (JWT mode)", exchange.getRequest().getPath());
        return unauthorizedResponse(exchange, "Missing authentication. Provide JWT token via Authorization: Bearer header or API key via X-API-Key header");
    }
    
    /**
     * API_KEY mode: Only accept API key authentication.
     */
    private Mono<Void> authenticateWithApiKeyOnly(ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return authenticateWithApiKey(apiKey, exchange, chain);
        }

        log.warn("Missing API key in request to {} (API_KEY mode)", exchange.getRequest().getPath());
        return unauthorizedResponse(exchange, "Missing API key. Provide via X-API-Key header");
    }

    /**
     * Authenticate using JWT token.
     */
    private Mono<Void> authenticateWithJwt(String token, ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        if (!jwtProperties.isEnabled()) {
            return unauthorizedResponse(exchange, "JWT authentication is disabled");
        }

        try {
            // Validate token
            String clientId = jwtService.getClientIdFromToken(token);
            String tokenType = jwtService.getTokenType(token);
            String tokenId = jwtService.getTokenId(token);

            // Check token type
            if (!"access".equals(tokenType)) {
                log.warn("Invalid token type for API access: {}", tokenType);
                return unauthorizedResponse(exchange, "Invalid token type. Use access token.");
            }

            // Check blacklist
            if (tokenBlacklistService.isBlacklisted(tokenId)) {
                log.warn("Attempt to use blacklisted token: {}", tokenId);
                return unauthorizedResponse(exchange, "Token has been revoked");
            }

            // Authentication successful - populate SecurityContext
            log.debug("JWT authentication successful for client: {}", clientId);
            
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                clientId, 
                token, 
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            
            return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));

        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return unauthorizedResponse(exchange, "Invalid or expired JWT token");
        }
    }

    /**
     * Authenticate using API key.
     */
    private Mono<Void> authenticateWithApiKey(String apiKey, ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        // Check configured API keys
        boolean validKey = apiKeyProperties.getApiKeys().stream()
                .anyMatch(client -> client.getKey().equals(apiKey));

        // Also check legacy API key for backward compatibility
        if (!validKey && legacyMcpApiKey != null && !legacyMcpApiKey.trim().isEmpty()) {
            validKey = legacyMcpApiKey.equals(apiKey);
        }

        if (!validKey) {
            log.warn("Invalid API key provided for {}", exchange.getRequest().getPath());
            return unauthorizedResponse(exchange, "Invalid API key");
        }

        // Authentication successful - populate SecurityContext
        String clientId = apiKeyProperties.getApiKeys().stream()
            .filter(client -> client.getKey().equals(apiKey))
            .map(ApiKeyProperties.ApiKeyClient::getClientId)
            .findFirst()
            .orElse("legacy-client");
        
        log.debug("API key authentication successful for client: {}", clientId);
        
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            clientId,
            apiKey,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        
        return chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    /**
     * Return 401 Unauthorized response with error message.
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("WWW-Authenticate", "API-Key");
        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory()
                .wrap(("{\"error\": \"" + message + "\"}").getBytes()))
        );
    }
}
