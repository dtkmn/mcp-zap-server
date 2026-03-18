package mcp.server.zap.core.logging;

import org.slf4j.MDC;

/**
 * Thread-local correlation id holder mirrored into MDC for structured logging.
 */
public final class RequestCorrelationHolder {
    public static final String CORRELATION_ID_KEY = "asg.request.correlation-id";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private RequestCorrelationHolder() {
    }

    public static String currentCorrelationId() {
        return CORRELATION_ID.get();
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            clearCorrelationId();
            return;
        }

        String normalized = correlationId.trim();
        CORRELATION_ID.set(normalized);
        MDC.put(RequestLogContext.CORRELATION_ID_MDC_KEY, normalized);
    }

    public static void clearCorrelationId() {
        CORRELATION_ID.remove();
        MDC.remove(RequestLogContext.CORRELATION_ID_MDC_KEY);
    }
}
