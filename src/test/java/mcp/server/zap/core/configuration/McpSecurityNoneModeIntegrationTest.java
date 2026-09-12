package mcp.server.zap.core.configuration;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.tool.McpToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
                "mcp.server.tools.surface=guided",
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=none",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.protection.enabled=false"
        }
)
@ActiveProfiles("test")
class McpSecurityNoneModeIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String UNKNOWN_TOOL = "nonexistent_none_mode_test_tool";
    private static final String DISABLED_TOOL = "zap_spider_status";

    @LocalServerPort
    private int port;

    @Autowired
    private McpToolRegistry activeToolRegistry;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

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
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("\"result\"")
                        .contains("\"tools\"")
                        .doesNotContain("insufficient_scope"));
    }

    @Test
    void noneModeStillTreatsUnknownAndDisabledToolsAsTheSameGenericError() throws Exception {
        String sessionId = initializeSession();
        assertThat(activeToolRegistry.names()).doesNotContain(UNKNOWN_TOOL, DISABLED_TOOL);
        JsonNode expected = OBJECT_MAPPER.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", "none-mode-unavailable",
                "error", Map.of("code", -32602, "message", "Unknown tool")
        ));

        for (String toolName : List.of(UNKNOWN_TOOL, DISABLED_TOOL)) {
            EntityExchangeResult<String> result = post(sessionId, Map.of(
                    "jsonrpc", "2.0",
                    "id", "none-mode-unavailable",
                    "method", "tools/call",
                    "params", Map.of("name", toolName, "arguments", Map.of())
            ));

            assertThat(result.getStatus().value()).isEqualTo(200);
            assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
            assertThat(responseEnvelope(result)).isEqualTo(expected);
        }
    }

    @Test
    void noneModeDiscoveryMatchesTheRegisteredCallbacksAndActiveRegistry() throws Exception {
        String sessionId = initializeSession();
        EntityExchangeResult<String> result = post(sessionId, Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list"
        ));

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        List<String> advertisedNames = new ArrayList<>();
        for (JsonNode tool : responseEnvelope(result).path("result").path("tools")) {
            advertisedNames.add(tool.path("name").asString());
        }
        List<String> registeredNames = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();

        assertThat(advertisedNames)
                .containsExactlyInAnyOrderElementsOf(registeredNames)
                .containsExactlyInAnyOrderElementsOf(activeToolRegistry.names())
                .contains("zap_passive_scan_status")
                .doesNotContain(UNKNOWN_TOOL, DISABLED_TOOL);
    }

    private EntityExchangeResult<String> post(String sessionId, Map<String, Object> request) throws Exception {
        return client().post()
                .uri("/mcp")
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(OBJECT_MAPPER.writeValueAsString(request))
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private JsonNode responseEnvelope(EntityExchangeResult<String> result) throws Exception {
        String body = result.getResponseBody();
        assertThat(body).isNotBlank();
        MediaType contentType = result.getResponseHeaders().getContentType();
        if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            List<String> messages = Arrays.stream(body.split("\\r?\\n\\r?\\n"))
                    .map(event -> event.lines()
                            .filter(line -> line.startsWith("data:"))
                            .map(line -> line.substring(5).stripLeading())
                            .collect(java.util.stream.Collectors.joining("\n")))
                    .filter(data -> !data.isBlank())
                    .toList();
            assertThat(messages).hasSize(1);
            return OBJECT_MAPPER.readTree(messages.get(0));
        }
        return OBJECT_MAPPER.readTree(body);
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
        EntityExchangeResult<String> notification = post(sessionId, Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized"
        ));
        assertThat(notification.getStatus().value()).isEqualTo(202);
        assertThat(notification.getResponseBody()).isNullOrEmpty();
        return sessionId;
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
