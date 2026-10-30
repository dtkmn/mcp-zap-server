package mcp.server.zap.integration;

import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.server.zap.RetiredExpertTools;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
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
                "mcp.server.tools.surface=expert",
                "MCP_SERVER_TOOLS_SURFACE=expert",
                "mcp.server.security.authorization.allow-wildcard=true",
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
                "mcp.server.auth.apiKeys[2].scopes[0]=zap:report:read",
                "mcp.server.auth.apiKeys[3].clientId=legacy-expert-client",
                "mcp.server.auth.apiKeys[3].key=legacy-expert-api-key",
                "mcp.server.auth.apiKeys[3].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[3].scopes[1]=zap:scan:active:run",
                "mcp.server.auth.apiKeys[4].clientId=wildcard-client",
                "mcp.server.auth.apiKeys[4].key=wildcard-api-key",
                "mcp.server.auth.apiKeys[4].scopes[0]=*",
                "mcp.server.auth.apiKeys[5].clientId=reconcile-client",
                "mcp.server.auth.apiKeys[5].key=reconcile-api-key",
                "mcp.server.auth.apiKeys[5].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[5].scopes[1]=zap:scan:queue:reconcile"
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

    @Autowired private ToolAuthorizationProperties authorizationProperties;
    @Autowired private AbuseProtectionProperties protectionProperties;
    @MockitoBean private EngineScanExecution scanExecution;
    @MockitoBean private EngineAjaxSpiderExecution ajaxExecution;
    @MockitoBean private EngineFindingAccess findingAccess;

    @AfterEach
    void restoreGovernanceModes() {
        authorizationProperties.setMode(ToolAuthorizationProperties.Mode.ENFORCE);
        protectionProperties.setEnabled(false);
    }

    @BeforeAll
    static void createReportFile() throws Exception {
        reportDirectory = Files.createTempDirectory("mcp-authz-reports").toRealPath();
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zap_report_read", "zap_report_read_chunk"})
    void toolCallReturns403WhenJwtLacksToolScope(String toolName) throws Exception {
        String token = issueAccessToken("lister-api-key");
        String sessionId = initializeSession(token);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 1,
                "params", Map.of(
                        "name", toolName,
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
                .jsonPath("$.tool").isEqualTo(toolName)
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"legacy-expert-api-key", "wildcard-api-key"})
    void retiredAliasIsUnavailableForOldOrWildcardJwtScopesInEveryGovernanceMode(String key) throws Exception {
        String token = issueAccessToken(key);
        String session = initializeSession(token);
        clearInvocations(scanExecution, ajaxExecution, findingAccess);
        JsonNode expected = OBJECT_MAPPER.valueToTree(Map.of("jsonrpc", "2.0", "id", "retired-jwt",
                "error", Map.of("code", -32602, "message", "Unknown tool")));
        for (ToolAuthorizationProperties.Mode mode : ToolAuthorizationProperties.Mode.values()) {
            authorizationProperties.setMode(mode);
            for (boolean protection : new boolean[]{false, true}) {
                protectionProperties.setEnabled(protection);
                String request = OBJECT_MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "retired-jwt",
                        "method", "tools/call", "params", Map.of("name", "zap_active_scan_start", "arguments", Map.of())));
                EntityExchangeResult<String> response = client().post().uri("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Mcp-Session-Id", session)
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
                        .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                        .expectBody(String.class).returnResult();
                assertThat(OBJECT_MAPPER.readTree(response.getResponseBody())).isEqualTo(expected);
            }
        }
        verifyNoInteractions(scanExecution, ajaxExecution, findingAccess);
    }

    @Test
    void oldExpertScanScopeDoesNotBecomeGuidedAttackPermission() throws Exception {
        String token = issueAccessToken("legacy-expert-api-key");
        String session = initializeSession(token);
        clearInvocations(scanExecution, ajaxExecution);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "no-scope-escalation", "method", "tools/call",
                "params", Map.of("name", "zap_attack_start", "arguments", Map.of("targetUrl", "https://example.com"))));
        client().post().uri("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Mcp-Session-Id", session)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.requiredScopes[0]").isEqualTo("zap:scan:attack:run");
        verifyNoInteractions(scanExecution, ajaxExecution);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"reconcile-api-key", "wildcard-api-key"})
    void unconfiguredRecoveryToolsStayUnknownDespiteExactOrWildcardScopes(String key) throws Exception {
        String token = issueAccessToken(key);
        String session = initializeSession(token);
        clearInvocations(scanExecution, ajaxExecution, findingAccess);
        JsonNode expected = OBJECT_MAPPER.valueToTree(Map.of("jsonrpc", "2.0", "id", "optional-recovery",
                "error", Map.of("code", -32602, "message", "Unknown tool")));
        for (ToolAuthorizationProperties.Mode mode : ToolAuthorizationProperties.Mode.values()) {
            authorizationProperties.setMode(mode);
            for (boolean protection : new boolean[]{false, true}) {
                protectionProperties.setEnabled(protection);
                for (String tool : List.of("zap_scan_job_reconcile", "zap_scan_job_reconciliation_status")) {
                    String request = OBJECT_MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "optional-recovery",
                            "method", "tools/call", "params", Map.of("name", tool, "arguments", Map.of())));
                    EntityExchangeResult<String> response = client().post().uri("/mcp")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Mcp-Session-Id", session)
                            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
                            .expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                            .expectBody(String.class).returnResult();
                    assertThat(OBJECT_MAPPER.readTree(response.getResponseBody())).isEqualTo(expected);
                }
            }
        }
        verifyNoInteractions(scanExecution, ajaxExecution, findingAccess);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zap_findings_snapshot", "zap_findings_diff"})
    void retainedFindingsToolsStillRequireAlertsReadScopeBeforeEngineAccess(String tool) throws Exception {
        String token = issueAccessToken("reporter-api-key");
        String session = initializeSession(token);
        clearInvocations(findingAccess);
        String request = OBJECT_MAPPER.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "scoped-findings", "method", "tools/call",
                "params", Map.of("name", tool, "arguments", Map.of("baseUrl", "https://unowned.example"))));
        client().post().uri("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("Mcp-Session-Id", session)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.requiredScopes[0]").isEqualTo("zap:alerts:read");
        verifyNoInteractions(findingAccess);
    }

    private static Object[] unknownToolRequestIds() {
        return new Object[]{"unknown-tool-request", 9_007_199_254_740_993L};
    }

    @Test
    void staleExpertSettingStillDiscoversOnlyTheCuratedGuidedInventory() throws Exception {
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
        assertThat(advertisedNames).hasSize(28).contains("zap_passive_scan_status", "zap_findings_snapshot", "zap_findings_diff")
                .doesNotContain("zap_scan_job_reconcile", "zap_scan_job_reconciliation_status")
                .doesNotContainAnyElementsOf(RetiredExpertTools.NAMES)
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

    @Test
    void authorizedJwtRetrievesReportPagesToEndOfFile() throws Exception {
        String token = issueAccessToken("reporter-api-key");
        String sessionId = initializeSession(token);
        JsonNode first = readReportPage(token, sessionId,
                Map.of("reportPath", reportFile.toString(), "maxChars", 4));
        assertThat(first.path("content").asString()).isEqualTo("jwt ");
        assertThat(first.path("endOfFile").asBoolean()).isFalse();

        JsonNode last = readReportPage(token, sessionId, Map.of(
                "reportPath", reportFile.toString(),
                "offset", first.path("nextOffset").asLong(),
                "expectedSha256", first.path("artifactSha256").asString()));
        assertThat(last.path("endOfFile").asBoolean()).isTrue();
        assertThat(last.has("nextOffset")).isTrue();
        assertThat(last.path("nextOffset").isNull()).isTrue();
        assertThat(first.path("content").asString() + last.path("content").asString())
                .isEqualTo(Files.readString(reportFile));
    }

    private JsonNode readReportPage(String token, String sessionId, Map<String, Object> arguments) throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                "params", Map.of("name", "zap_report_read_chunk", "arguments", arguments)));
        String body = client().post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        String json = body.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).stripLeading()).findFirst().orElse(body);
        JsonNode result = OBJECT_MAPPER.readTree(json).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        return OBJECT_MAPPER.readTree(result.path("content").get(0).path("text").asString());
    }
}
