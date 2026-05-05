package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.request.max-body-bytes=1024",
                "mcp.server.security.enabled=false",
                "mcp.server.security.authorization.mode=off",
                "mcp.server.protection.enabled=false"
        }
)
@ActiveProfiles("test")
class McpRequestBodyLimitIntegrationTest {
    @LocalServerPort
    private int port;

    @Test
    void oversizedMcpPostIsRejectedBeforeJsonParsing() {
        String oversizedRequest = """
                {"jsonrpc":"2.0","method":"tools/list","id":1,"padding":"
                """
                + "x".repeat(2_000)
                + "\"}";

        WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build()
                .post()
                .uri("/mcp")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(oversizedRequest)
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.error").isEqualTo("request_body_too_large")
                .jsonPath("$.maxBodyBytes").isEqualTo(1024);
    }
}
