package mcp.server.zap.core.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.jwt.enabled=true",
                "mcp.server.auth.rate-limit.enabled=true",
                "mcp.server.auth.rate-limit.capacity=1",
                "mcp.server.auth.rate-limit.refill-tokens=1",
                "mcp.server.auth.rate-limit.refill-period-seconds=3600"
        }
)
@ActiveProfiles("test")
class AuthEndpointRateLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AuditEventRepository auditEventRepository;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void publicAuthExchangeEndpointsAreThrottled() {
        List<AuthExchangeRequest> requests = List.of(
                new AuthExchangeRequest("/auth/token", Map.of("apiKey", "invalid-api-key")),
                new AuthExchangeRequest("/auth/refresh", Map.of("refreshToken", "invalid-refresh-token")),
                new AuthExchangeRequest("/auth/revoke", Map.of("token", "invalid-access-token"))
        );

        for (AuthExchangeRequest request : requests) {
            postJson(request)
                    .expectStatus().isUnauthorized();

            postJson(request)
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists("Retry-After")
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("Too Many Requests")
                    .jsonPath("$.message").isEqualTo("Too many authentication requests. Try again later.");

            assertThat(authRateLimitRejectionCount(request.path())).isEqualTo(1.0d);
        }

        List<String> auditedEndpoints = auditEventRepository
                .find("anonymous", Instant.EPOCH, "auth_rate_limit_rejection")
                .stream()
                .map(event -> event.getData().get("endpoint"))
                .map(String.class::cast)
                .toList();
        assertThat(auditedEndpoints).contains("/auth/token", "/auth/refresh", "/auth/revoke");
    }

    private WebTestClient.ResponseSpec postJson(AuthExchangeRequest request) {
        return webTestClient().post()
                .uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchange();
    }

    private double authRateLimitRejectionCount(String endpoint) {
        return Optional.ofNullable(meterRegistry.find("mcp.zap.auth.rate_limit.rejections")
                        .tag("endpoint", endpoint)
                        .counter())
                .map(counter -> counter.count())
                .orElse(0.0d);
    }

    private record AuthExchangeRequest(String path, Map<String, String> body) {
    }
}
