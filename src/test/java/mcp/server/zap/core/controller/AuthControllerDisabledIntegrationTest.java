package mcp.server.zap.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.jwt.enabled=false"
        }
)
@ActiveProfiles("test")
class AuthControllerDisabledIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void authEndpointsAreNotExposedWhenJwtSupportIsDisabled() {
        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .post()
                .uri("/auth/token")
                .header("X-API-Key", "test-api-key")
                .exchange()
                .expectStatus().isNotFound();
    }
}
