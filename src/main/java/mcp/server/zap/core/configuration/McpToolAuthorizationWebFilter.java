package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Enforces tool-level scope authorization for MCP JSON-RPC requests.
 */
@Component
public class McpToolAuthorizationWebFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(McpToolAuthorizationWebFilter.class);

    private final ObjectMapper objectMapper;
    private final McpJsonRpcInvocationParser invocationParser;
    private final String mcpEndpoint;
    private final int maxBodyBytes;
    private final ToolAuthorizationService toolAuthorizationService;
    private final ObservabilityService observabilityService;
    private final ClientWorkspaceResolver clientWorkspaceResolver;

    public McpToolAuthorizationWebFilter(ObjectProvider<ObjectMapper> objectMapperProvider,
                                         @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
                                         @Value("${mcp.server.request.max-body-bytes:262144}") int maxBodyBytes,
                                         ToolAuthorizationService toolAuthorizationService,
                                         ObservabilityService observabilityService,
                                         ClientWorkspaceResolver clientWorkspaceResolver) {
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.invocationParser = new McpJsonRpcInvocationParser(this.objectMapper);
        this.mcpEndpoint = normalizeEndpoint(mcpEndpoint);
        this.maxBodyBytes = Math.max(1024, maxBodyBytes);
        this.toolAuthorizationService = toolAuthorizationService;
        this.observabilityService = observabilityService;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || toolAuthorizationService.isDisabled()) {
            return chain.filter(exchange);
        }
        if (contentLengthExceedsLimit(exchange)) {
            return writePayloadTooLarge(exchange);
        }

        return exchange.getPrincipal()
                .cast(Authentication.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optionalAuthentication -> optionalAuthentication
                        .<Mono<Void>>map(authentication -> cacheAndAuthorize(exchange, chain, authentication))
                        .orElseGet(() -> chain.filter(exchange)));
    }

    private boolean isRelevantRequest(ServerWebExchange exchange) {
        return "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && mcpEndpoint.equals(exchange.getRequest().getPath().value());
    }

    private Mono<Void> cacheAndAuthorize(ServerWebExchange exchange,
                                         WebFilterChain chain,
                                         Authentication authentication) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxBodyBytes)
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    McpToolInvocation invocation = invocationParser.parse(bodyBytes);
                    if (!invocation.authorizable()) {
                        return chain.filter(decorateExchange(exchange, bodyBytes));
                    }

                    List<String> grantedScopes = extractGrantedScopes(authentication);
                    String correlationId = RequestLogContext.correlationId(exchange);
                    GatewayToolExecutionContext toolContext = clientWorkspaceResolver.resolveToolExecutionContext(
                            authentication,
                            correlationId,
                            invocation,
                            null
                    );
                    ToolAuthorizationDecision decision = toolAuthorizationService.authorize(grantedScopes, toolContext);

                    if (!decision.mapped()) {
                        log.warn("Denied MCP action {} because no scope mapping exists", invocation.actionName());
                        observabilityService.recordAuthorization(
                                decision.actionName(),
                                toolAuthorizationService.isEnforced() ? "denied" : "warn",
                                "unmapped_tool",
                                decision.requiredScopes(),
                                decision.grantedScopes(),
                                toolContext.principalId(),
                                toolContext.workspaceId(),
                                correlationId
                        );
                        if (toolAuthorizationService.isEnforced()) {
                            return writeForbidden(exchange, decision, "unmapped_tool");
                        }
                    }

                    if (!decision.allowed()) {
                        observabilityService.recordAuthorization(
                                decision.actionName(),
                                toolAuthorizationService.isWarnOnly() ? "warn" : "denied",
                                "insufficient_scope",
                                decision.requiredScopes(),
                                decision.grantedScopes(),
                                toolContext.principalId(),
                                toolContext.workspaceId(),
                                correlationId
                        );
                        if (toolAuthorizationService.isWarnOnly()) {
                            log.warn("Allowing insufficient-scope MCP action {} in warn mode. Missing scopes: {} granted: {}",
                                    invocation.actionName(), decision.missingScopes(), decision.grantedScopes());
                            return chain.filter(decorateExchange(exchange, bodyBytes));
                        }
                        return writeForbidden(exchange, decision, "insufficient_scope");
                    }

                    observabilityService.recordAuthorization(
                            decision.actionName(),
                            "allowed",
                            "scope_granted",
                            decision.requiredScopes(),
                            decision.grantedScopes(),
                            toolContext.principalId(),
                            toolContext.workspaceId(),
                            correlationId
                    );
                    return chain.filter(decorateExchange(exchange, bodyBytes));
                })
                .onErrorResume(DataBufferLimitException.class, ignored -> writePayloadTooLarge(exchange));
    }

    private boolean contentLengthExceedsLimit(ServerWebExchange exchange) {
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        return contentLength > maxBodyBytes;
    }

    private ServerWebExchange decorateExchange(ServerWebExchange exchange, byte[] bodyBytes) {
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.CONTENT_LENGTH);
                headers.setContentLength(bodyBytes.length);
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.just(bufferFactory.wrap(bodyBytes)));
            }
        };
        return exchange.mutate().request(decoratedRequest).build();
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange,
                                      ToolAuthorizationDecision decision,
                                      String errorCode) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", decision.requiredScopes()) + "\"");

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.FORBIDDEN.value());
        body.put("error", errorCode);
        body.put("tool", decision.actionName());
        body.put("requiredScopes", decision.requiredScopes());
        body.put("grantedScopes", decision.grantedScopes());
        body.put("correlationId", RequestLogContext.correlationId(exchange));
        body.put("requestId", exchange.getRequest().getId());

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Unable to serialize insufficient-scope response for {}: {}", decision.actionName(), e.getMessage(), e);
            byte[] fallback = ("{\"error\":\"insufficient_scope\",\"tool\":\"" + decision.actionName() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(fallback)));
        }
    }

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        body.put("error", "request_body_too_large");
        body.put("reason", "MCP request body exceeds the configured limit");
        body.put("maxBodyBytes", maxBodyBytes);
        body.put("correlationId", RequestLogContext.correlationId(exchange));
        body.put("requestId", exchange.getRequest().getId());

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Unable to serialize request-body-limit response: {}", e.getMessage(), e);
            byte[] fallback = "{\"error\":\"request_body_too_large\"}".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(fallback)));
        }
    }

    private List<String> extractGrantedScopes(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of();
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith("SCOPE_"))
                .map(authority -> authority.substring("SCOPE_".length()))
                .map(scope -> scope.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/mcp";
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
    }
}
