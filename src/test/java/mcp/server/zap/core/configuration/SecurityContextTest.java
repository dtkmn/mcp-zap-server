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
                "mcp.server.security.mode=jwt",
                "mcp.server.auth.jwt.enabled=true"
        }
)
@ActiveProfiles("test")
class SecurityContextTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private record TokenPair(String accessToken, String refreshToken) {}

    private TokenPair issueTokens() {
        EntityExchangeResult<Map> result = webTestClient().post()
                .uri("/auth/token")
                .header("X-API-Key", "test-api-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        return new TokenPair((String) body.get("accessToken"), (String) body.get("refreshToken"));
    }

    @Test
    void protectedEndpointRejectsMissingAuthentication() {
        webTestClient().get()
                .uri("/auth/validate")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void jwtModeAllowsApiKeyFallbackForProtectedRoutes() {
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
    void jwtAuthenticationPopulatesSecurityContextAndAllowsProtectedRequest() {
        TokenPair tokens = issueTokens();

        webTestClient().get()
                .uri("/auth/validate")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(true)
                .jsonPath("$.clientId").isEqualTo("test-client");
    }

    @Test
    void revokedAccessTokenIsRejectedBeforeControllerExecution() {
        TokenPair tokens = issueTokens();

        webTestClient().post()
                .uri("/auth/revoke")
                .bodyValue(Map.of("token", tokens.accessToken()))
                .exchange()
                .expectStatus().isOk();

        webTestClient().get()
                .uri("/auth/validate")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
