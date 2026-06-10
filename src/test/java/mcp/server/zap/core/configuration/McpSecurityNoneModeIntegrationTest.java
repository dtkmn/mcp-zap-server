package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=none",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.protection.enabled=false"
        }
)
@ActiveProfiles("test")
class McpSecurityNoneModeIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void noneModeAllowsMcpToolsListWithoutAuthenticationOrToolScopes() throws Exception {
        String sessionId = initializeSession();

        client().post()
                .uri("/mcp")
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"result\"")
                        .contains("\"tools\"")
                        .doesNotContain("insufficient_scope"));
    }

    private String initializeSession() throws Exception {
        String initializeRequest = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "security-none-mode-test",
                                "version", "1.0.0"
                        )
                )
        ));

        EntityExchangeResult<String> result = client().post()
                .uri("/mcp")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(initializeRequest)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("Mcp-Session-Id")
                .expectBody(String.class)
                .returnResult();

        String sessionId = result.getResponseHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        return sessionId;
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
