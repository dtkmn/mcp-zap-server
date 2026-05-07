package mcp.server.zap.core.exception;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

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
