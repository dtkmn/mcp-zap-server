package mcp.server.zap.core.configuration;

import mcp.server.zap.core.service.JwtService;
import mcp.server.zap.core.service.TokenBlacklistService;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.AuthEndpointRateLimiter;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security configuration for the MCP ZAP Server.
 * Supports three authentication modes:
 * - NONE: No authentication (development/testing only)
 * - API_KEY: Simple API key authentication
 * - JWT: JWT token-based authentication (recommended for production)
 * 
 * CSRF PROTECTION: Intentionally DISABLED for all modes because:
 * 
 * 1. **API-Only Server**: This server provides a RESTful API for machine-to-machine 
 *    communication, not a browser-based web application.
 * 
 * 2. **Token-Based Authentication**: Uses JWT tokens and API keys sent via HTTP headers
 *    (Authorization/X-API-Key), NOT session cookies. CSRF attacks only work when 
 *    authentication credentials are automatically sent by browsers (cookies).
 * 
 * 3. **Stateless Architecture**: No session state or cookies are used. Each request 
 *    includes explicit authentication credentials.
 * 
 * 4. **MCP Protocol Requirement**: MCP clients (Claude Desktop, Cursor, mcpo) do not 
 *    support CSRF token exchange as part of the protocol specification.
 * 
 * This follows OWASP API Security best practices:
 * https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
 * (Section: "When to Use CSRF Protection" - Not applicable for stateless APIs)
 * 
 * Security is maintained through:
 * - Strong token-based authentication (JWT with 256-bit secrets)
 * - Token expiration and refresh mechanisms
 * - HTTPS in production (via reverse proxy)
 * - Input validation and URL whitelisting
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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
    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final ObjectProvider<TokenBlacklistService> tokenBlacklistServiceProvider;
    private final ObjectMapper objectMapper;
    private final ObservabilityService observabilityService;
    private final AuthEndpointRateLimiter authEndpointRateLimiter;

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final List<String> PUBLIC_AUTH_PATHS = List.of("/auth/token", "/auth/refresh", "/auth/revoke");
    private static final List<String> PUBLIC_ACTUATOR_PATHS = List.of("/actuator/health", "/actuator/info");

    public SecurityConfig(JwtProperties jwtProperties,
                         ApiKeyProperties apiKeyProperties,
                         ObjectProvider<JwtService> jwtServiceProvider,
                         ObjectProvider<TokenBlacklistService> tokenBlacklistServiceProvider,
                         ObjectProvider<ObjectMapper> objectMapperProvider,
                         ObservabilityService observabilityService,
                         AuthEndpointRateLimiter authEndpointRateLimiter) {
        this.jwtProperties = jwtProperties;
        this.apiKeyProperties = apiKeyProperties;
        this.jwtServiceProvider = jwtServiceProvider;
        this.tokenBlacklistServiceProvider = tokenBlacklistServiceProvider;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.observabilityService = observabilityService;
        this.authEndpointRateLimiter = authEndpointRateLimiter;
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
        // CSRF protection: Disabled for API-only server with token-based authentication
        log.info("Security mode: {} - Authentication required for all endpoints (except public paths)", mode);
        log.info("CSRF protection: DISABLED (API-only server with token-based auth, no cookies)");
        http
            .csrf(csrf -> csrf.disable())  // Disable CSRF for API-only server (MCP protocol doesn't support CSRF)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health", "/actuator/info", "/auth/token", "/auth/refresh", "/auth/revoke").permitAll()
                .anyExchange().authenticated()
            )
            .addFilterAt(authEndpointRateLimitFilter(), SecurityWebFiltersOrder.FIRST)
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
            if (isPublicEndpoint(path)) {
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

    private WebFilter authEndpointRateLimitFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!isPublicAuthPath(path)) {
                return chain.filter(exchange);
            }

            String key = authRateLimitKey(exchange, path);
            if (authEndpointRateLimiter.tryConsume(key)) {
                return chain.filter(exchange);
            }

            long retryAfterSeconds = authEndpointRateLimiter.retryAfterSeconds(key);
            observabilityService.recordAuthRateLimitRejection(path, RequestLogContext.correlationId(exchange));
            return tooManyAuthRequestsResponse(exchange, retryAfterSeconds);
        };
    }

    private boolean isPublicEndpoint(String path) {
        return PUBLIC_ACTUATOR_PATHS.contains(path) || isPublicAuthPath(path);
    }

    private boolean isPublicAuthPath(String path) {
        return PUBLIC_AUTH_PATHS.contains(path);
    }

    private String authRateLimitKey(ServerWebExchange exchange, String path) {
        String remoteAddress = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(address -> address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString())
                .filter(value -> value != null && !value.isBlank())
                .orElse("unknown");
        return remoteAddress + ":" + path;
    }
    
    /**
     * JWT mode: Try JWT first, fall back to API key for backward compatibility.
     */
    private Mono<Void> authenticateWithJwtFirst(ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return authenticateWithJwt(token, exchange, chain, "jwt");
        }

        // Fall back to API key authentication
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return authenticateWithApiKey(apiKey, exchange, chain, "api_key");
        }

        log.warn("Missing authentication in request to {} (JWT mode)", exchange.getRequest().getPath());
        observabilityService.recordAuthentication(
                "jwt",
                "failure",
                "missing_authentication",
                "anonymous",
                "default-workspace",
                RequestLogContext.correlationId(exchange)
        );
        return unauthorizedResponse(exchange, "Missing authentication. Provide JWT token via Authorization: Bearer header or API key via X-API-Key header");
    }
    
    /**
     * API_KEY mode: Only accept API key authentication.
     */
    private Mono<Void> authenticateWithApiKeyOnly(ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return authenticateWithApiKey(apiKey, exchange, chain, "api_key");
        }

        log.warn("Missing API key in request to {} (API_KEY mode)", exchange.getRequest().getPath());
        observabilityService.recordAuthentication(
                "api_key",
                "failure",
                "missing_api_key",
                "anonymous",
                "default-workspace",
                RequestLogContext.correlationId(exchange)
        );
        return unauthorizedResponse(exchange, "Missing API key. Provide via X-API-Key header");
    }

    /**
     * Authenticate using JWT token.
     */
    private Mono<Void> authenticateWithJwt(String token,
                                           ServerWebExchange exchange,
                                           org.springframework.web.server.WebFilterChain chain,
                                           String authMethod) {
        if (!jwtProperties.isEnabled()) {
            observabilityService.recordAuthentication(
                    authMethod,
                    "failure",
                    "jwt_disabled",
                    "anonymous",
                    "default-workspace",
                    RequestLogContext.correlationId(exchange)
            );
            return unauthorizedResponse(exchange, "JWT authentication is disabled");
        }

        JwtService jwtService = jwtServiceProvider.getIfAvailable();
        TokenBlacklistService tokenBlacklistService = tokenBlacklistServiceProvider.getIfAvailable();
        if (jwtService == null || tokenBlacklistService == null) {
            log.error("JWT authentication requested but JWT support beans are not available");
            observabilityService.recordAuthentication(
                    authMethod,
                    "failure",
                    "jwt_unavailable",
                    "anonymous",
                    "default-workspace",
                    RequestLogContext.correlationId(exchange)
            );
            return unauthorizedResponse(exchange, "JWT authentication is unavailable");
        }

        try {
            // Validate token
            String clientId = jwtService.getClientIdFromToken(token);
            String tokenType = jwtService.getTokenType(token);
            String tokenId = jwtService.getTokenId(token);
            List<String> scopes = jwtService.getScopesFromToken(token);

            // Check token type
            if (!"access".equals(tokenType)) {
                log.warn("Invalid token type for API access: {}", tokenType);
                observabilityService.recordAuthentication(
                        authMethod,
                        "failure",
                        "invalid_token_type",
                        "anonymous",
                        "default-workspace",
                        RequestLogContext.correlationId(exchange)
                );
                return unauthorizedResponse(exchange, "Invalid token type. Use access token.");
            }

            // Check blacklist
            if (tokenBlacklistService.isBlacklisted(tokenId)) {
                log.warn("Attempt to use blacklisted token: {}", tokenId);
                observabilityService.recordAuthentication(
                        authMethod,
                        "failure",
                        "revoked_token",
                        clientId,
                        resolveWorkspaceId(clientId),
                        RequestLogContext.correlationId(exchange)
                );
                return unauthorizedResponse(exchange, "Token has been revoked");
            }

            // Authentication successful - populate SecurityContext
            log.debug("JWT authentication successful for client: {}", clientId);
            
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                clientId, 
                token, 
                buildAuthorities(scopes)
            );
            
            return filterWithAuthentication(exchange, chain, authentication, authMethod);

        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            observabilityService.recordAuthentication(
                    authMethod,
                    "failure",
                    "invalid_or_expired_jwt",
                    "anonymous",
                    "default-workspace",
                    RequestLogContext.correlationId(exchange)
            );
            return unauthorizedResponse(exchange, "Invalid or expired JWT token");
        }
    }

    /**
     * Authenticate using API key.
     */
    private Mono<Void> authenticateWithApiKey(String apiKey,
                                              ServerWebExchange exchange,
                                              org.springframework.web.server.WebFilterChain chain,
                                              String authMethod) {
        // Check configured API keys
        Optional<ApiKeyProperties.ApiKeyClient> clientOpt = apiKeyProperties.getApiKeys().stream()
                .filter(client -> client.getKey().equals(apiKey))
                .findFirst();

        boolean validKey = clientOpt.isPresent();

        // Also check legacy API key for backward compatibility
        if (!validKey && legacyMcpApiKey != null && !legacyMcpApiKey.trim().isEmpty()) {
            validKey = legacyMcpApiKey.equals(apiKey);
        }

        if (!validKey) {
            log.warn("Invalid API key provided for {}", exchange.getRequest().getPath());
            observabilityService.recordAuthentication(
                    authMethod,
                    "failure",
                    "invalid_api_key",
                    "anonymous",
                    "default-workspace",
                    RequestLogContext.correlationId(exchange)
            );
            return unauthorizedResponse(exchange, "Invalid API key");
        }

        // Authentication successful - populate SecurityContext
        String clientId = clientOpt
            .map(ApiKeyProperties.ApiKeyClient::getClientId)
            .orElse("legacy-client");
        List<String> scopes = clientOpt
                .map(ApiKeyProperties.ApiKeyClient::getScopes)
                .orElse(List.of("*"));
        
        log.debug("API key authentication successful for client: {}", clientId);
        
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            clientId,
            apiKey,
            buildAuthorities(scopes)
        );
        
        return filterWithAuthentication(exchange, chain, authentication, authMethod);
    }

    private Mono<Void> filterWithAuthentication(ServerWebExchange exchange,
                                                org.springframework.web.server.WebFilterChain chain,
                                                Authentication authentication,
                                                String authMethod) {
        return Mono.defer(() -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            String clientId = authentication.getName();
            String workspaceId = resolveWorkspaceId(clientId);
            SecurityContextHolder.setContext(context);
            RequestIdentityHolder.set(clientId, workspaceId);
            exchange.getAttributes().put(RequestLogContext.CLIENT_ID_ATTRIBUTE, clientId);
            exchange.getAttributes().put(RequestLogContext.WORKSPACE_ID_ATTRIBUTE, workspaceId);
            observabilityService.recordAuthentication(
                    authMethod,
                    "success",
                    "authenticated",
                    clientId,
                    workspaceId,
                    RequestLogContext.correlationId(exchange)
            );
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                    .contextCapture()
                    .doFinally(signalType -> {
                        SecurityContextHolder.clearContext();
                        RequestIdentityHolder.clear();
                    });
        });
    }

    private String resolveWorkspaceId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return "default-workspace";
        }

        return apiKeyProperties.getApiKeys().stream()
                .filter(client -> client != null && clientId.equals(client.getClientId()))
                .map(ApiKeyProperties.ApiKeyClient::getWorkspaceId)
                .filter(workspaceId -> workspaceId != null && !workspaceId.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(clientId.trim());
    }

    private Collection<? extends GrantedAuthority> buildAuthorities(List<String> scopes) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

        LinkedHashSet<String> normalizedScopes = new LinkedHashSet<>();
        if (scopes != null) {
            scopes.stream()
                    .filter(scope -> scope != null && !scope.isBlank())
                    .map(scope -> scope.trim().toLowerCase(Locale.ROOT))
                    .forEach(normalizedScopes::add);
        }

        for (String scope : normalizedScopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return authorities;
    }

    /**
     * Return 401 Unauthorized response with error message.
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("WWW-Authenticate", "API-Key");
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");

        String correlationId = RequestLogContext.correlationId(exchange);
        if (correlationId != null && !correlationId.isBlank()) {
            exchange.getResponse().getHeaders().set(RequestLogContext.CORRELATION_ID_HEADER, correlationId);
        }

        try {
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("error", message);
            body.put("correlationId", correlationId != null ? correlationId : RequestCorrelationHolder.currentCorrelationId());
            body.put("requestId", exchange.getRequest().getId());
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
            );
        } catch (Exception e) {
            String escapedMessage = message == null ? "Unauthorized" : message.replace("\"", "\\\"");
            String escapedCorrelationId = correlationId == null ? "" : correlationId.replace("\"", "\\\"");
            String fallback = "{\"error\":\"" + escapedMessage + "\",\"correlationId\":\"" + escapedCorrelationId + "\"}";
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(fallback.getBytes()))
            );
        }
    }

    private Mono<Void> tooManyAuthRequestsResponse(ServerWebExchange exchange, long retryAfterSeconds) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1L, retryAfterSeconds)));
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        String correlationId = RequestLogContext.correlationId(exchange);
        if (correlationId != null && !correlationId.isBlank()) {
            exchange.getResponse().getHeaders().set(RequestLogContext.CORRELATION_ID_HEADER, correlationId);
        }

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
                    "error", "Too Many Requests",
                    "message", "Too many authentication requests. Try again later.",
                    "correlationId", correlationId != null ? correlationId : RequestCorrelationHolder.currentCorrelationId(),
                    "requestId", exchange.getRequest().getId()
            ));
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
            );
        } catch (Exception e) {
            String fallback = "{\"error\":\"Too Many Requests\",\"message\":\"Too many authentication requests. Try again later.\"}";
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(fallback.getBytes()))
            );
        }
    }
}
