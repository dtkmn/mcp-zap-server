package mcp.server.zap.core.configuration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.tool.McpToolRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=jwt",
                "mcp.server.auth.jwt.enabled=true",
                "mcp.server.auth.apiKeys[0].clientId=reporter-client",
                "mcp.server.auth.apiKeys[0].key=reporter-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:report:read",
                "mcp.server.auth.apiKeys[1].clientId=lister-client",
                "mcp.server.auth.apiKeys[1].key=lister-api-key",
                "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[2].clientId=no-list-client",
                "mcp.server.auth.apiKeys[2].key=no-list-api-key",
                "mcp.server.auth.apiKeys[2].scopes[0]=zap:report:read"
        }
)
@ActiveProfiles("test")
class McpToolAuthorizationJwtIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Path reportDirectory;
    private static Path reportFile;

    @LocalServerPort
    private int port;

    @Autowired
    private McpToolRegistry mcpActiveToolRegistry;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @BeforeAll
    static void createReportFile() throws Exception {
        reportDirectory = Files.createTempDirectory("mcp-authz-reports");
        reportFile = reportDirectory
                .resolve("workspaces")
                .resolve("reporter-client")
                .resolve("mcp-authz-report.txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, "jwt integration report");
    }

    @DynamicPropertySource
    static void registerReportDirectory(DynamicPropertyRegistry registry) {
        registry.add("zap.report.directory", () -> reportDirectory.toString());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private EntityExchangeResult<String> exchangeWithJwt(String token, String body) {
        return client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private String initializeSession(String token) throws Exception {
        String initializeRequest = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "authz-jwt-test",
                                "version", "1.0.0"
                        )
                )
        ));

        EntityExchangeResult<String> result = exchangeWithJwt(token, initializeRequest);
        assertThat(result.getStatus().value()).isEqualTo(200);
        String sessionId = result.getResponseHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        return sessionId;
    }

    private String issueAccessToken(String apiKey) {
        EntityExchangeResult<Map> result = client().post()
                .uri("/auth/token")
                .header("X-API-Key", apiKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        Map<?, ?> body = result.getResponseBody();
        assertThat(body).isNotNull();
        return (String) body.get("accessToken");
    }

    @Test
    void toolsListRequiresDiscoveryScope() throws Exception {
        String token = issueAccessToken("no-list-api-key");

        client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.tool").isEqualTo("mcp:tools:list")
                .jsonPath("$.requiredScopes[0]").isEqualTo("mcp:tools:list");
    }

    @Test
    void toolCallReturns403WhenJwtLacksToolScope() throws Exception {
        String token = issueAccessToken("lister-api-key");
        String sessionId = initializeSession(token);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 1,
                "params", Map.of(
                        "name", "zap_report_read",
                        "arguments", Map.of("reportPath", reportFile.toString(), "maxChars", 1000)
                )
        ));

        client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> assertThat(value)
                        .contains("Bearer", "error=\"insufficient_scope\"", "scope=\"zap:report:read\""))
                .expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.tool").isEqualTo("zap_report_read")
                .jsonPath("$.requiredScopes[0]").isEqualTo("zap:report:read");
    }

    @ParameterizedTest(name = "unknown tool preserves JSON-RPC request ID {0}")
    @MethodSource("unknownToolRequestIds")
    void unknownToolReturnsProtocolErrorWithoutPermissionOrCatalogDetails(Object requestId) throws Exception {
        String token = issueAccessToken("lister-api-key");
        String sessionId = initializeSession(token);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", requestId,
                "params", Map.of(
                        "name", "__unknown_tool_for_authz_regression__",
                        "arguments", Map.of()
                )
        ));
        JsonNode expectedResponse = OBJECT_MAPPER.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", requestId,
                "error", Map.of("code", -32602, "message", "Unknown tool")
        ));

        client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)
                .expectBody(String.class)
                .value(body -> {
                    // Exact envelope equality also rejects scope, mapping, and tool-catalog disclosures.
                    assertThat(OBJECT_MAPPER.readTree(body)).isEqualTo(expectedResponse);
                });
    }

    private static Object[] unknownToolRequestIds() {
        return new Object[]{"unknown-tool-request", 9_007_199_254_740_993L};
    }

    @Test
    void expertDiscoveryMatchesTheExactActiveRegistryAndToolProvider() throws Exception {
        String token = issueAccessToken("lister-api-key");
        String sessionId = initializeSession(token);
        EntityExchangeResult<String> result = client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"tools/list\"}")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();

        String body = result.getResponseBody();
        assertThat(body).isNotBlank();
        String json = body.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).stripLeading()).findFirst().orElse(body);
        List<String> advertisedNames = new ArrayList<>();
        for (JsonNode tool : OBJECT_MAPPER.readTree(json).path("result").path("tools")) {
            advertisedNames.add(tool.path("name").asString());
        }
        assertThat(advertisedNames).contains("zap_passive_scan_status", "zap_spider_status")
                .containsExactlyInAnyOrderElementsOf(mcpActiveToolRegistry.names());
        assertThat(mcpActiveToolRegistry.names()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(toolCallbackProvider.getToolCallbacks())
                        .map(callback -> callback.getToolDefinition().name()).toList());
    }

    @Test
    void authorizedJwtToolCallCanReadReportArtifact() throws Exception {
        String token = issueAccessToken("reporter-api-key");
        String sessionId = initializeSession(token);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 1,
                "params", Map.of(
                        "name", "zap_report_read",
                        "arguments", Map.of("reportPath", reportFile.toString(), "maxChars", 1000)
                )
        ));

        client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("jwt integration report"));
    }
}
