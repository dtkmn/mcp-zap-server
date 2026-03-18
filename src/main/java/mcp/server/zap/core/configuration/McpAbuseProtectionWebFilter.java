package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.logging.RequestLogContext;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.McpAbuseProtectionDecision;
import mcp.server.zap.core.service.protection.McpAbuseProtectionService;
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
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Applies rate limits, workspace quotas, and overload shedding to MCP JSON-RPC traffic.
 */
@Component
public class McpAbuseProtectionWebFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(McpAbuseProtectionWebFilter.class);

    private final ObjectMapper objectMapper;
    private final String mcpEndpoint;
    private final McpAbuseProtectionService protectionService;
    private final ObservabilityService observabilityService;

    public McpAbuseProtectionWebFilter(ObjectProvider<ObjectMapper> objectMapperProvider,
                                       @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
                                       McpAbuseProtectionService protectionService,
                                       ObservabilityService observabilityService) {
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.mcpEndpoint = normalizeEndpoint(mcpEndpoint);
        this.protectionService = protectionService;
        this.observabilityService = observabilityService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isRelevantRequest(exchange) || !protectionService.isEnabled()) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .cast(Authentication.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optionalAuthentication -> optionalAuthentication
                        .<Mono<Void>>map(authentication -> DataBufferUtils.join(exchange.getRequest().getBody())
                                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                                .flatMap(dataBuffer -> {
                                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bodyBytes);
                                    DataBufferUtils.release(dataBuffer);

                                    JsonRpcAction action = parseAction(bodyBytes);
                                    McpAbuseProtectionDecision decision = protectionService.evaluate(
                                            authentication,
                                            action.method(),
                                            action.toolName()
                                    );
                                    if (!decision.allowed()) {
                                        log.warn("Rejected MCP action {} for client {} workspace {} due to {} ({})",
                                                decision.toolName(), decision.clientId(), decision.workspaceId(),
                                                decision.errorCode(), decision.reason());
                                        observabilityService.recordProtectionRejection(
                                                decision,
                                                RequestLogContext.correlationId(exchange)
                                        );
                                        return writeRejected(exchange, decision);
                                    }

                                    return chain.filter(decorateExchange(exchange, bodyBytes));
                                }))
                        .orElseGet(() -> chain.filter(exchange)));
    }

    private boolean isRelevantRequest(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() != null
                && "POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                && mcpEndpoint.equals(exchange.getRequest().getPath().value());
    }

    private JsonRpcAction parseAction(byte[] bodyBytes) {
        if (bodyBytes.length == 0) {
            return new JsonRpcAction(null, null);
        }

        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            String method = textValue(root.get("method"));
            if (!"tools/call".equals(method)) {
                return new JsonRpcAction(method, null);
            }
            JsonNode params = root.get("params");
            String toolName = params != null ? textValue(params.get("name")) : null;
            return new JsonRpcAction(method, toolName);
        } catch (Exception e) {
            return new JsonRpcAction(null, null);
        }
    }

    private Mono<Void> writeRejected(ServerWebExchange exchange, McpAbuseProtectionDecision decision) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, decision.retryAfterSeconds())));

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", decision.errorCode());
        body.put("reason", decision.reason());
        body.put("tool", decision.toolName());
        body.put("clientId", decision.clientId());
        body.put("workspaceId", decision.workspaceId());
        body.put("retryAfterSeconds", Math.max(1L, decision.retryAfterSeconds()));
        body.put("correlationId", RequestLogContext.correlationId(exchange));
        body.put("requestId", exchange.getRequest().getId());

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            byte[] fallback = ("{\"error\":\"" + decision.errorCode() + "\",\"reason\":\"" + decision.reason() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(fallback)));
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

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "/mcp";
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    @Override
    public int getOrder() {
        return SecurityWebFiltersOrder.AUTHORIZATION.getOrder() + 2;
    }

    private record JsonRpcAction(String method, String toolName) {
    }
}
