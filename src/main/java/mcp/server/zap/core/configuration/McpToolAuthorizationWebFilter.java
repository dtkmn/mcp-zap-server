package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolAuthorizationDecision;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Enforces tool-level scope authorization for MCP JSON-RPC requests.
 */
@Component
public class McpToolAuthorizationWebFilter implements WebFilter, Ordered {
    private static final String JSON_RPC_METHOD_TOOLS_CALL = "tools/call";
    private static final String JSON_RPC_METHOD_TOOLS_LIST = "tools/list";
    private static final Logger log = LoggerFactory.getLogger(McpToolAuthorizationWebFilter.class);

    private final ObjectMapper objectMapper;
    private final String mcpEndpoint;
    private final ToolAuthorizationService toolAuthorizationService;
    private final ObservabilityService observabilityService;

    public McpToolAuthorizationWebFilter(ObjectProvider<ObjectMapper> objectMapperProvider,
                                         @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
                                         ToolAuthorizationService toolAuthorizationService,
                                         ObservabilityService observabilityService) {
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.mcpEndpoint = normalizeEndpoint(mcpEndpoint);
        this.toolAuthorizationService = toolAuthorizationService;
        this.observabilityService = observabilityService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || toolAuthorizationService.isDisabled()) {
            return chain.filter(exchange);
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
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    AuthorizationRequest authorizationRequest = parseAuthorizationRequest(bodyBytes);
                    if (authorizationRequest == null) {
                        return chain.filter(decorateExchange(exchange, bodyBytes));
                    }

                    List<String> grantedScopes = extractGrantedScopes(authentication);
                    ToolAuthorizationDecision decision = switch (authorizationRequest.kind()) {
                        case TOOLS_LIST -> toolAuthorizationService.authorizeToolsList(grantedScopes);
                        case TOOL_CALL -> toolAuthorizationService.authorizeToolCall(grantedScopes, authorizationRequest.toolName());
                    };

                    if (!decision.mapped()) {
                        log.warn("Denied MCP action {} because no scope mapping exists", authorizationRequest.toolName());
                        observabilityService.recordAuthorization(
                                decision.actionName(),
                                toolAuthorizationService.isEnforced() ? "denied" : "warn",
                                "unmapped_tool",
                                decision.requiredScopes(),
                                decision.grantedScopes(),
                                resolveClientId(authentication),
                                resolveWorkspaceId(exchange),
                                RequestLogContext.correlationId(exchange)
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
                                resolveClientId(authentication),
                                resolveWorkspaceId(exchange),
                                RequestLogContext.correlationId(exchange)
                        );
                        if (toolAuthorizationService.isWarnOnly()) {
                            log.warn("Allowing insufficient-scope MCP action {} in warn mode. Missing scopes: {} granted: {}",
                                    authorizationRequest.toolName(), decision.missingScopes(), decision.grantedScopes());
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
                            resolveClientId(authentication),
                            resolveWorkspaceId(exchange),
                            RequestLogContext.correlationId(exchange)
                    );
                    return chain.filter(decorateExchange(exchange, bodyBytes));
                });
    }

    private AuthorizationRequest parseAuthorizationRequest(byte[] bodyBytes) {
        if (bodyBytes.length == 0) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            if (root == null || !root.isObject()) {
                return null;
            }

            String method = textValue(root.get("method"));
            if (JSON_RPC_METHOD_TOOLS_LIST.equals(method)) {
                return new AuthorizationRequest(RequestKind.TOOLS_LIST, JSON_RPC_METHOD_TOOLS_LIST);
            }
            if (JSON_RPC_METHOD_TOOLS_CALL.equals(method)) {
                JsonNode params = root.get("params");
                String toolName = params != null ? textValue(params.get("name")) : null;
                if (toolName == null || toolName.isBlank()) {
                    return null;
                }
                return new AuthorizationRequest(RequestKind.TOOL_CALL, toolName.trim());
            }
            return null;
        } catch (Exception e) {
            log.debug("Skipping MCP scope inspection because request body could not be parsed as JSON: {}", e.getMessage());
            return null;
        }
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

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/mcp";
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private String resolveClientId(Authentication authentication) {
        return authentication != null && authentication.getName() != null && !authentication.getName().isBlank()
                ? authentication.getName().trim()
                : "anonymous";
    }

    private String resolveWorkspaceId(ServerWebExchange exchange) {
        Object workspaceId = exchange.getAttribute(RequestLogContext.WORKSPACE_ID_ATTRIBUTE);
        if (workspaceId instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return "default-workspace";
    }

    private enum RequestKind {
        TOOLS_LIST,
        TOOL_CALL
    }

    private record AuthorizationRequest(RequestKind kind, String toolName) {
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 1;
    }
}
