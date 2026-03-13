package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.jwt.enabled=true"
        }
)
@ActiveProfiles("test")
class ApiKeyModeSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private String issueAccessToken() {
        EntityExchangeResult<Map> result = webTestClient().post()
                .uri("/auth/token")
                .header("X-API-Key", "test-api-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        return (String) body.get("accessToken");
    }

    @Test
    void protectedEndpointRejectsMissingApiKey() {
        webTestClient().get()
                .uri("/auth/validate")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedEndpointAcceptsApiKeyAuthentication() {
        webTestClient().get()
                .uri("/auth/validate")
                .header("X-API-Key", "test-api-key")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid Request")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("Authorization"));
    }

    @Test
    void apiKeyModeRejectsJwtOnlyRequestWithoutApiKey() {
        String accessToken = issueAccessToken();

        webTestClient().get()
                .uri("/auth/validate")
                .header("Authorization", "Bearer " + accessToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
