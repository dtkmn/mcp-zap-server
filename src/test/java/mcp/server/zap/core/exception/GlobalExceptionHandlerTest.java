package mcp.server.zap.core.exception;

import java.util.Map;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.logging.RequestLogContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void engineBusyReturnsTemporaryUnavailabilityWithReasonAndCorrelation() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/mcp")
                        .header(RequestLogContext.CORRELATION_ID_HEADER, "busy-request-123")
                        .build()
        );

        ResponseEntity<Map<String, Object>> response = handler.handleEngineBusyException(
                new EngineBusyException("ZAP is busy with another AJAX Spider scan", new RuntimeException("upstream details")),
                exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", 503)
                .containsEntry("error", "Engine Busy")
                .containsEntry("message", "ZAP is busy with another AJAX Spider scan")
                .containsEntry("correlationId", "busy-request-123");
        assertThat(response.getBody().get("message").toString()).doesNotContain("upstream details");
    }

    @Test
    void illegalStateExceptionReturnsStableMessageWithoutRawDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/mcp").build()
        );

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalStateException(
                new IllegalStateException("Postgres password=secret failed"),
                exchange
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("message", "The server could not complete the request.");
        assertThat(response.getBody().get("message").toString()).doesNotContain("secret", "Postgres");
    }
}
