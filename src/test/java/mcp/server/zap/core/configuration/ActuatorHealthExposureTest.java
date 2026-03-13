package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ActuatorHealthExposureTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void healthEndpointDoesNotExposeSensitiveDetails() {
        webTestClient().get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isIn(200, 503))
                .expectBody()
                .jsonPath("$.status").exists()
                .jsonPath("$..version").doesNotExist()
                .jsonPath("$..error").doesNotExist();
    }
}
