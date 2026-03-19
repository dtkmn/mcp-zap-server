package mcp.server.zap.core.logging;

import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared request diagnostics constants and correlation-id helpers.
 */
public final class RequestLogContext {
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String LEGACY_REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_ATTRIBUTE = "asg.request.correlation-id";
    public static final String CLIENT_ID_ATTRIBUTE = "asg.request.client-id";
    public static final String WORKSPACE_ID_ATTRIBUTE = "asg.request.workspace-id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String CLIENT_ID_MDC_KEY = "clientId";
    public static final String WORKSPACE_ID_MDC_KEY = "workspaceId";

    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");

    private RequestLogContext() {
    }

    public static String resolveCorrelationId(String correlationIdHeader, String legacyRequestIdHeader) {
        String normalized = sanitizeCorrelationId(correlationIdHeader);
        if (normalized != null) {
            return normalized;
        }

        normalized = sanitizeCorrelationId(legacyRequestIdHeader);
        if (normalized != null) {
            return normalized;
        }

        return UUID.randomUUID().toString();
    }

    public static String sanitizeCorrelationId(String candidate) {
        if (candidate == null) {
            return null;
        }

        String normalized = candidate.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_CORRELATION_ID_LENGTH) {
            return null;
        }

        return SAFE_CORRELATION_ID.matcher(normalized).matches() ? normalized : null;
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
