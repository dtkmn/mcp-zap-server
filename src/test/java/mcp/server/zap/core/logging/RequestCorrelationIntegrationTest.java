package mcp.server.zap.core.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.apiKeys[0].clientId=structured-client",
                "mcp.server.auth.apiKeys[0].workspaceId=shared-workspace",
                "mcp.server.auth.apiKeys[0].key=structured-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=*"
        }
)
@ActiveProfiles("test")
class RequestCorrelationIntegrationTest {
    private Logger requestLogger;
    private ListAppender<ILoggingEvent> requestLogAppender;

    @LocalServerPort
    private int port;

    @BeforeEach
    void attachLogAppender() {
        requestLogger = (Logger) LoggerFactory.getLogger(RequestCorrelationWebFilter.class);
        requestLogAppender = new ListAppender<>();
        requestLogAppender.start();
        requestLogger.addAppender(requestLogAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (requestLogger != null && requestLogAppender != null) {
            requestLogger.detachAppender(requestLogAppender);
            requestLogAppender.stop();
        }
    }

    @Test
    void correlationIdPropagatesToResponseErrorBodyAndStructuredCompletionLog() {
        EntityExchangeResult<Map> result = client().get()
                .uri("/auth/validate")
                .header("X-API-Key", "structured-api-key")
                .header(RequestLogContext.CORRELATION_ID_HEADER, "corr-123")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(RequestLogContext.CORRELATION_ID_HEADER, "corr-123")
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("correlationId")).isEqualTo("corr-123");

        ILoggingEvent completionEvent = lastCompletionEvent();
        assertThat(completionEvent.getFormattedMessage()).contains("path=/auth/validate");
        assertThat(completionEvent.getFormattedMessage()).contains("status=400");
        assertThat(completionEvent.getMDCPropertyMap())
                .containsEntry(RequestLogContext.CORRELATION_ID_MDC_KEY, "corr-123")
                .containsEntry(RequestLogContext.CLIENT_ID_MDC_KEY, "structured-client")
                .containsEntry(RequestLogContext.WORKSPACE_ID_MDC_KEY, "shared-workspace");
    }

    @Test
    void unauthorizedResponseGeneratesCorrelationIdWhenClientDoesNotProvideOne() {
        EntityExchangeResult<Map> result = client().get()
                .uri("/auth/validate")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestLogContext.CORRELATION_ID_HEADER)
                .expectBody(Map.class)
                .returnResult();

        String correlationId = result.getResponseHeaders().getFirst(RequestLogContext.CORRELATION_ID_HEADER);
        Map<?, ?> body = result.getResponseBody();
        assertThat(correlationId).isNotBlank();
        assertThat(body).isNotNull();
        assertThat(body.get("correlationId")).isEqualTo(correlationId);

        ILoggingEvent completionEvent = lastCompletionEvent();
        assertThat(completionEvent.getMDCPropertyMap())
                .containsEntry(RequestLogContext.CORRELATION_ID_MDC_KEY, correlationId)
                .containsEntry(RequestLogContext.CLIENT_ID_MDC_KEY, "anonymous")
                .containsEntry(RequestLogContext.WORKSPACE_ID_MDC_KEY, "anonymous");
    }

    @Test
    void legacyRequestIdHeaderFallsBackToCorrelationIdContract() {
        EntityExchangeResult<Map> result = client().get()
                .uri("/auth/validate")
                .header(RequestLogContext.LEGACY_REQUEST_ID_HEADER, "legacy-req-123")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(RequestLogContext.CORRELATION_ID_HEADER, "legacy-req-123")
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("correlationId")).isEqualTo("legacy-req-123");

        ILoggingEvent completionEvent = lastCompletionEvent();
        assertThat(completionEvent.getMDCPropertyMap())
                .containsEntry(RequestLogContext.CORRELATION_ID_MDC_KEY, "legacy-req-123");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private ILoggingEvent lastCompletionEvent() {
        long deadlineNanos = System.nanoTime() + 2_000_000_000L;
        List<ILoggingEvent> events = completionEvents();
        while (events.isEmpty() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            events = completionEvents();
        }
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private List<ILoggingEvent> completionEvents() {
        return requestLogAppender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("request.completed"))
                .toList();
    }
}
