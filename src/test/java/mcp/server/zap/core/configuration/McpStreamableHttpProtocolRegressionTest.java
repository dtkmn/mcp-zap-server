package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Pscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.jwt.enabled=true",
                "mcp.server.auth.apiKeys[0].clientId=claude-desktop",
                "mcp.server.auth.apiKeys[0].key=claude-desktop-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:scan:read",
                "mcp.server.protection.enabled=false"
        }
)
@ActiveProfiles("test")
@Import(McpStreamableHttpProtocolRegressionTest.MockZapConfig.class)
class McpStreamableHttpProtocolRegressionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String API_KEY = "claude-desktop-api-key";
    private static final String LATEST_PROTOCOL_VERSION = "2025-11-25";
    private static final String LEGACY_STREAMABLE_PROTOCOL_VERSION = "2025-06-18";

    @LocalServerPort
    private int port;

    @Value("${spring.ai.mcp.server.version}")
    private String configuredServerVersion;

    @Test
    void latestProtocolSessionHandlesListPingAndToolCallFollowUps() throws Exception {
        Session session = initializeSession(LATEST_PROTOCOL_VERSION, Map.of(
                "roots", Map.of("listChanged", true),
                "sampling", Map.of(),
                "elicitation", Map.of("form", Map.of(), "url", Map.of())
        ));

        assertThat(session.negotiatedProtocolVersion()).isEqualTo(LATEST_PROTOCOL_VERSION);
        assertThat(session.serverInfoVersion()).isEqualTo(configuredServerVersion);
        assertFollowUpSucceeds(session, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", "zap_passive_scan_status");
        assertFollowUpSucceeds(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", "\"result\":{}");
        assertFollowUpSucceeds(
                session,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"zap_passive_scan_status\",\"arguments\":{}}}",
                "Passive scan status"
        );
    }

    @Test
    void legacyStreamableProtocolStillHandlesFollowUps() throws Exception {
        Session session = initializeSession(LEGACY_STREAMABLE_PROTOCOL_VERSION, Map.of());

        assertThat(session.negotiatedProtocolVersion()).isEqualTo(LEGACY_STREAMABLE_PROTOCOL_VERSION);
        assertFollowUpSucceeds(session, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}", "zap_passive_scan_status");
    }

    private Session initializeSession(String protocolVersion, Map<String, Object> capabilities) throws Exception {
        String initializeRequest = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", protocolVersion,
                        "capabilities", capabilities,
                        "clientInfo", Map.of(
                                "name", "claude-desktop-regression",
                                "version", "1.0.0"
                        )
                )
        ));

        EntityExchangeResult<String> result = client().post()
                .uri("/mcp")
                .header("X-API-Key", API_KEY)
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

        JsonNode envelope = OBJECT_MAPPER.readTree(result.getResponseBody());
        String negotiatedProtocolVersion = envelope.path("result").path("protocolVersion").asText();
        assertThat(negotiatedProtocolVersion).isNotBlank();
        String serverInfoVersion = envelope.path("result").path("serverInfo").path("version").asText();
        assertThat(serverInfoVersion).isNotBlank();
        return new Session(sessionId, negotiatedProtocolVersion, serverInfoVersion);
    }

    private void assertFollowUpSucceeds(Session session, String payload, String expectedBodyFragment) {
        client().post()
                .uri("/mcp")
                .header("X-API-Key", API_KEY)
                .header("Mcp-Session-Id", session.sessionId())
                .header("MCP-Protocol-Version", session.negotiatedProtocolVersion())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).doesNotContain("NullPointerException");
                    assertThat(body).contains(expectedBodyFragment);
                });
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private record Session(String sessionId, String negotiatedProtocolVersion, String serverInfoVersion) {
    }

    @TestConfiguration
    static class MockZapConfig {
        @Bean
        @Primary
        ClientApi mockZapClientApi() throws Exception {
            ClientApi clientApi = new ClientApi("localhost", 0);
            Pscan pscan = mock(Pscan.class);
            when(pscan.recordsToScan()).thenReturn(new ApiResponseElement("recordsToScan", "0"));
            when(pscan.scanOnlyInScope()).thenReturn(new ApiResponseElement("scanOnlyInScope", "false"));
            when(pscan.currentTasks()).thenReturn(new ApiResponseList("tasks"));
            clientApi.pscan = pscan;
            return clientApi;
        }
    }
}
