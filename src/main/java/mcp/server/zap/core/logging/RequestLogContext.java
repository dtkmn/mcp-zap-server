package mcp.server.zap.core.logging;

import mcp.gateway.core.logging.CorrelationIds;
import org.springframework.web.server.ServerWebExchange;

/**
 * Shared request diagnostics constants and correlation-id helpers.
 */
public final class RequestLogContext {
    public static final String CORRELATION_ID_HEADER = CorrelationIds.CORRELATION_ID_HEADER;
    public static final String LEGACY_REQUEST_ID_HEADER = CorrelationIds.LEGACY_REQUEST_ID_HEADER;
    public static final String CORRELATION_ID_ATTRIBUTE = "mcp.zap.request.correlation-id";
    public static final String CLIENT_ID_ATTRIBUTE = "mcp.zap.request.client-id";
    public static final String WORKSPACE_ID_ATTRIBUTE = "mcp.zap.request.workspace-id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String CLIENT_ID_MDC_KEY = "clientId";
    public static final String WORKSPACE_ID_MDC_KEY = "workspaceId";

    private RequestLogContext() {
    }

    public static String resolveCorrelationId(String correlationIdHeader, String legacyRequestIdHeader) {
        return CorrelationIds.resolve(correlationIdHeader, legacyRequestIdHeader);
    }

    public static String sanitizeCorrelationId(String candidate) {
        return CorrelationIds.sanitize(candidate);
    }

    public static String correlationId(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (value instanceof String correlationId && !correlationId.isBlank()) {
            return correlationId;
        }

        String headerValue = sanitizeCorrelationId(exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER));
        if (headerValue != null) {
            return headerValue;
        }

        headerValue = sanitizeCorrelationId(exchange.getResponse().getHeaders().getFirst(CORRELATION_ID_HEADER));
        if (headerValue != null) {
            return headerValue;
        }

        return RequestCorrelationHolder.currentCorrelationId();
    }
}
