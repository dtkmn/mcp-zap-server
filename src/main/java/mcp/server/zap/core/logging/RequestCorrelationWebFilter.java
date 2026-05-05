package mcp.server.zap.core.logging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mcp.server.zap.core.observability.ObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Assigns a stable correlation id to every request, reflects it in response
 * headers, and emits a structured completion log line.
 */
@Component
public class RequestCorrelationWebFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationWebFilter.class);
    private final ObservabilityService observabilityService;

    public RequestCorrelationWebFilter(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Instant startedAt = Instant.now();
        String correlationId = RequestLogContext.resolveCorrelationId(
                exchange.getRequest().getHeaders().getFirst(RequestLogContext.CORRELATION_ID_HEADER),
                exchange.getRequest().getHeaders().getFirst(RequestLogContext.LEGACY_REQUEST_ID_HEADER)
        );

        exchange.getAttributes().put(RequestLogContext.CORRELATION_ID_ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(RequestLogContext.CORRELATION_ID_HEADER, correlationId);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(addCorrelationHeader(exchange.getRequest(), correlationId))
                .build();
        mutatedExchange.getAttributes().putAll(exchange.getAttributes());

        return Mono.defer(() -> {
            RequestCorrelationHolder.setCorrelationId(correlationId);
            return chain.filter(mutatedExchange)
                    .contextCapture()
                    .doFinally(signalType -> {
                        logCompletion(mutatedExchange, correlationId, startedAt, signalType.name());
                        RequestCorrelationHolder.clearCorrelationId();
                    });
        });
    }

    private ServerHttpRequest addCorrelationHeader(ServerHttpRequest request, String correlationId) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.put(RequestLogContext.CORRELATION_ID_HEADER, List.of(correlationId));
                return headers;
            }
        };
    }

    private void logCompletion(ServerWebExchange exchange, String correlationId, Instant startedAt, String signalType) {
        Duration duration = Duration.between(startedAt, Instant.now());
        long durationMs = duration.toMillis();
        String method = exchange.getRequest().getMethod() != null ? exchange.getRequest().getMethod().name() : "UNKNOWN";
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String clientId = stringAttribute(exchange, RequestLogContext.CLIENT_ID_ATTRIBUTE);
        String workspaceId = stringAttribute(exchange, RequestLogContext.WORKSPACE_ID_ATTRIBUTE);
        int statusCode = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value()
                : 0;
        String status = statusCode > 0 ? Integer.toString(statusCode) : "unknown";

        observabilityService.recordHttpRequest(method, path, statusCode, clientId, workspaceId, duration);

        try (MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable(RequestLogContext.CORRELATION_ID_MDC_KEY, correlationId);
             MDC.MDCCloseable ignoredClient = MDC.putCloseable(RequestLogContext.CLIENT_ID_MDC_KEY, clientId);
             MDC.MDCCloseable ignoredWorkspace = MDC.putCloseable(RequestLogContext.WORKSPACE_ID_MDC_KEY, workspaceId)) {
            if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
                log.debug("request.completed correlationId={} method={} path={} status={} durationMs={} signal={} clientId={} workspaceId={}",
                        correlationId, method, path, status, durationMs, signalType, clientId, workspaceId);
                return;
            }

            log.info("request.completed correlationId={} method={} path={} status={} durationMs={} signal={} clientId={} workspaceId={}",
                    correlationId, method, path, status, durationMs, signalType, clientId, workspaceId);
        }
    }

    private String stringAttribute(ServerWebExchange exchange, String attributeName) {
        Object value = exchange.getAttribute(attributeName);
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return "anonymous";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
