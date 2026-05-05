package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.jwt.enabled=true",
                "mcp.server.auth.apiKeys[0].clientId=reporter-client",
                "mcp.server.auth.apiKeys[0].key=reporter-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:report:read",
                "mcp.server.auth.apiKeys[1].clientId=lister-client",
                "mcp.server.auth.apiKeys[1].key=lister-api-key",
                "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[2].clientId=policy-client",
                "mcp.server.auth.apiKeys[2].key=policy-api-key",
                "mcp.server.auth.apiKeys[2].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[2].scopes[1]=zap:policy:dry-run"
        }
)
@ActiveProfiles("test")
class McpToolAuthorizationApiKeyIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Path reportFile;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createReportFile() throws Exception {
        reportFile = Files.createTempFile("mcp-authz-api-key-report", ".txt");
        Files.writeString(reportFile, "api-key integration report");
    }

    @DynamicPropertySource
    static void registerReportDirectory(DynamicPropertyRegistry registry) {
        registry.add("zap.report.directory", () -> reportFile.getParent().toString());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private EntityExchangeResult<String> exchangeWithApiKey(String apiKey, String body) {
        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    private String initializeSession(String apiKey) throws Exception {
        String initializeRequest = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "authz-api-key-test",
                                "version", "1.0.0"
                        )
                )
        ));

        EntityExchangeResult<String> result = exchangeWithApiKey(apiKey, initializeRequest);
        assertThat(result.getStatus().value()).isEqualTo(200);
        String sessionId = result.getResponseHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        return sessionId;
    }

    @Test
    void apiKeyClientGetsSameInsufficientScopeContract() throws Exception {
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
                .header("X-API-Key", "lister-api-key")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.tool").isEqualTo("zap_report_read")
                .jsonPath("$.requiredScopes[0]").isEqualTo("zap:report:read");
    }

    @Test
    void apiKeyClientWithScopeCanCallReportRead() throws Exception {
        String sessionId = initializeSession("reporter-api-key");
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
                .header("X-API-Key", "reporter-api-key")
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("api-key integration report"));
    }

    @Test
    void findingsSummaryDoesNotTreatReportReadAsAlertRead() throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 2,
                "params", Map.of(
                        "name", "zap_get_findings_summary",
                        "arguments", Map.of()
                )
        ));

        client().post()
                .uri("/mcp")
                .header("X-API-Key", "reporter-api-key")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope")
                .jsonPath("$.tool").isEqualTo("zap_get_findings_summary")
                .jsonPath("$.requiredScopes[0]").isEqualTo("zap:alerts:read");
    }

    @Test
    void policyClientCanCallDryRunPreviewTool() throws Exception {
        String sessionId = initializeSession("policy-api-key");
        JsonNode contract = policyDryRunContract(
                "policy-api-key",
                sessionId,
                3,
                "https://api.sandbox.example.com/orders",
                "2026-04-06T09:00:00Z"
        );

        assertThat(contract.path("contractVersion").asText()).isEqualTo("mcp.zap.policy.dry-run/v1");
        assertThat(contract.path("decision").path("result").asText()).isEqualTo("allow");
        assertThat(contract.path("decision").path("matchedRuleId").asText()).isEqualTo("allow-sandbox-attack");
        assertThat(contract.path("request").path("normalizedHost").asText()).isEqualTo("api.sandbox.example.com");
    }

    @Test
    void policyDryRunContractStaysStableAcrossRepeatedAllowCalls() throws Exception {
        String sessionId = initializeSession("policy-api-key");

        JsonNode first = policyDryRunContract(
                "policy-api-key",
                sessionId,
                4,
                "https://api.sandbox.example.com/orders",
                "2026-04-06T09:00:00Z"
        );
        JsonNode second = policyDryRunContract(
                "policy-api-key",
                sessionId,
                5,
                "https://api.sandbox.example.com/orders",
                "2026-04-06T09:00:00Z"
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.path("decision").path("result").asText()).isEqualTo("allow");
        assertThat(first.path("decision").path("matchedRuleId").asText()).isEqualTo("allow-sandbox-attack");
    }

    @Test
    void policyDryRunContractStaysStableAcrossRepeatedBlockedCalls() throws Exception {
        String sessionId = initializeSession("policy-api-key");

        JsonNode first = policyDryRunContract(
                "policy-api-key",
                sessionId,
                6,
                "https://prod.example.com/orders",
                "2026-04-06T09:00:00Z"
        );
        JsonNode second = policyDryRunContract(
                "policy-api-key",
                sessionId,
                7,
                "https://prod.example.com/orders",
                "2026-04-06T09:00:00Z"
        );

        assertThat(first).isEqualTo(second);
        assertThat(first.path("validation").path("valid").asBoolean()).isTrue();
        assertThat(first.path("decision").path("result").asText()).isEqualTo("deny");
        assertThat(first.path("decision").path("source").asText()).isEqualTo("default");
        assertThat(first.path("decision").path("matchedRuleId").isNull()).isTrue();
    }

    private JsonNode policyDryRunContract(String apiKey,
                                          String sessionId,
                                          int requestId,
                                          String target,
                                          String evaluatedAt) throws Exception {
        EntityExchangeResult<String> result = callPolicyDryRun(apiKey, sessionId, requestId, target, evaluatedAt);
        JsonNode envelope = OBJECT_MAPPER.readTree(normalizeResponseBody(result.getResponseBody()));
        String text = envelope.path("result").path("content").path(0).path("text").asText();
        assertThat(text).isNotBlank();
        return OBJECT_MAPPER.readTree(text);
    }

    private EntityExchangeResult<String> callPolicyDryRun(String apiKey,
                                                          String sessionId,
                                                          int requestId,
                                                          String target,
                                                          String evaluatedAt) throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", requestId,
                "params", Map.of(
                        "name", "zap_policy_dry_run",
                        "arguments", Map.of(
                                "policyBundle", policyPreviewBundleJson(),
                                "toolName", "zap_attack_start",
                                "target", target,
                                "evaluatedAt", evaluatedAt
                        )
                )
        ));

        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
    }

    private String policyPreviewBundleJson() throws Exception {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "apiVersion", "mcp.zap.policy/v1",
                "kind", "PolicyBundle",
                "metadata", Map.of(
                        "name", "api-key-policy-preview",
                        "description", "Allow guided attack on sandbox",
                        "owner", "security-platform"
                ),
                "spec", Map.of(
                        "defaultDecision", "deny",
                        "evaluationOrder", "first-match",
                        "timezone", "UTC",
                        "rules", List.of(Map.of(
                                "id", "allow-sandbox-attack",
                                "description", "Allow sandbox attack",
                                "decision", "allow",
                                "reason", "sandbox rollout window",
                                "match", Map.of(
                                        "tools", List.of("zap_attack_start"),
                                        "hosts", List.of("api.sandbox.example.com"),
                                        "timeWindows", List.of(Map.of(
                                                "days", List.of("mon", "tue", "wed", "thu", "fri"),
                                                "start", "08:00",
                                                "end", "18:00"
                                        ))
                                )
                        ))
                )
        ));
    }

    private String normalizeResponseBody(String body) {
        if (body == null) {
            return "";
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        List<String> dataLines = trimmed.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .filter(line -> !line.isEmpty())
                .toList();
        if (!dataLines.isEmpty()) {
            return String.join("\n", dataLines);
        }
        return trimmed;
    }
}
