package mcp.server.zap.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerValidationTest {

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

    private TokenPair refreshWith(String refreshToken) {
        EntityExchangeResult<Map> result = webTestClient().post()
                .uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        return new TokenPair((String) body.get("accessToken"), (String) body.get("refreshToken"));
    }

    @Test
    void refreshTokenRequiresNonBlankToken() {
        webTestClient().post()
                .uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Validation Error")
                .jsonPath("$.message").value(message ->
                        org.assertj.core.api.Assertions.assertThat((String) message)
                                .contains("refreshToken: Refresh token is required"));
    }

    @Test
    void generateTokenRequiresNonBlankApiKeyWhenBodyProvided() {
        webTestClient().post()
                .uri("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"apiKey\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Validation Error")
                .jsonPath("$.message").value(message ->
                        org.assertj.core.api.Assertions.assertThat((String) message)
                                .contains("apiKey: API key is required"));
    }

    @Test
    void generateTokenAcceptsApiKeyFromHeaderWithoutBody() {
        webTestClient().post()
                .uri("/auth/token")
                .header("X-API-Key", "test-api-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.refreshToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void revokeTokenRequiresNonBlankToken() {
        webTestClient().post()
                .uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Validation Error")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("token: Token is required"));
    }

    @Test
    void revokeRefreshTokenBlocksFurtherRefresh() {
        TokenPair tokens = issueTokens();

        webTestClient().post()
                .uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", tokens.refreshToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.revoked").isEqualTo(true);

        webTestClient().post()
                .uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", tokens.refreshToken()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refreshShouldRotateTokenAndRejectReplay() {
        TokenPair initial = issueTokens();

        TokenPair rotated = refreshWith(initial.refreshToken());
        assertThat(rotated.refreshToken()).isNotBlank();
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.accessToken()).isNotEqualTo(initial.accessToken());

        // Replay attempt using consumed refresh token must be rejected.
        webTestClient().post()
                .uri("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", initial.refreshToken()))
                .exchange()
                .expectStatus().isUnauthorized();

        // New refresh token remains valid (one-time use) and rotates again.
        TokenPair rotatedAgain = refreshWith(rotated.refreshToken());
        assertThat(rotatedAgain.refreshToken()).isNotEqualTo(rotated.refreshToken());
    }

    @Test
    void validateTokenReportsRevokedAccessToken() {
        TokenPair tokens = issueTokens();

        webTestClient().post()
                .uri("/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("token", tokens.accessToken()))
                .exchange()
                .expectStatus().isOk();

        webTestClient().get()
                .uri("/auth/validate")
                .header("Authorization", "Bearer " + tokens.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.valid").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("Token has been revoked");
    }

    @Test
    void validateEndpointRequiresAuthorizationHeader() {
        webTestClient().get()
                .uri("/auth/validate")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Invalid Request")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("Authorization"));
    }
}
