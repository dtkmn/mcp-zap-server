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
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.auth.apiKeys[0].clientId=observer-client",
                "mcp.server.auth.apiKeys[0].workspaceId=obs-workspace",
                "mcp.server.auth.apiKeys[0].key=observer-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:report:read",
                "mcp.server.auth.apiKeys[1].clientId=limited-client",
                "mcp.server.auth.apiKeys[1].workspaceId=limited-workspace",
                "mcp.server.auth.apiKeys[1].key=limited-api-key",
                "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[2].clientId=policy-client",
                "mcp.server.auth.apiKeys[2].workspaceId=policy-workspace",
                "mcp.server.auth.apiKeys[2].key=policy-api-key",
                "mcp.server.auth.apiKeys[2].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[2].scopes[1]=zap:policy:dry-run"
        }
)
@ActiveProfiles("test")
class ObservabilityActuatorIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Path reportDirectory;
    private static Path reportFile;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createReportFile() throws Exception {
        reportDirectory = Files.createTempDirectory("observability-reports");
        reportFile = reportDirectory
                .resolve("workspaces")
                .resolve("obs-workspace")
                .resolve("observability-report.txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, "observability integration report");
    }

    @DynamicPropertySource
    static void registerReportDirectory(DynamicPropertyRegistry registry) {
        registry.add("zap.report.directory", () -> reportDirectory.toString());
    }

    @Test
    void prometheusMetricsAndAuditEndpointReflectObservedTraffic() throws Exception {
        client().get()
                .uri("/auth/validate")
                .header("X-Correlation-Id", "obs-auth-failure")
                .exchange()
                .expectStatus().isUnauthorized();

        client().get()
                .uri("/auth/validate")
                .header("X-API-Key", "observer-api-key")
                .header("X-Correlation-Id", "obs-auth-success")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.correlationId").isEqualTo("obs-auth-success");

        String limitedSessionId = initializeSession("limited-api-key");
        callReportRead("limited-api-key", limitedSessionId, "obs-authz-deny")
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope");

        String observerSessionId = initializeSession("observer-api-key");
        callReportRead("observer-api-key", observerSessionId, "obs-tool-success")
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("observability integration report"));

        EntityExchangeResult<String> authMetric = actuator("observer-api-key", "/actuator/metrics/mcp.zap.auth.events");
        assertThat(authMetric.getResponseBody())
                .contains("\"name\":\"mcp.zap.auth.events\"")
                .contains("\"tag\":\"method\"")
                .contains("\"tag\":\"outcome\"")
                .contains("\"tag\":\"reason\"");

        EntityExchangeResult<String> authzMetric = actuator("observer-api-key", "/actuator/metrics/mcp.zap.authorization.decisions");
        assertThat(authzMetric.getResponseBody())
                .contains("\"name\":\"mcp.zap.authorization.decisions\"")
                .contains("\"tag\":\"action\"")
                .contains("\"tag\":\"outcome\"")
                .contains("\"tag\":\"reason\"");

        EntityExchangeResult<String> toolMetric = actuator("observer-api-key", "/actuator/metrics/mcp.zap.tool.executions");
        assertThat(toolMetric.getResponseBody())
                .contains("\"name\":\"mcp.zap.tool.executions\"")
                .contains("\"tag\":\"tool\"")
                .contains("\"tag\":\"family\"")
                .contains("\"tag\":\"outcome\"");

        EntityExchangeResult<String> prometheus = actuator("observer-api-key", "/actuator/prometheus");
        assertThat(prometheus.getResponseBody())
                .contains("mcp_zap_http_requests_seconds_count")
                .contains("mcp_zap_auth_events_total")
                .contains("mcp_zap_authorization_decisions_total")
                .contains("mcp_zap_tool_executions_seconds_count")
                .contains("mcp_zap_audit_events_total")
                .contains("mcp_zap_queue_jobs")
                .contains("mcp_zap_operations_active");

        EntityExchangeResult<String> auditEvents = actuator("observer-api-key", "/actuator/auditevents");
        assertThat(auditEvents.getResponseBody())
                .contains("authentication")
                .contains("authorization")
                .contains("tool_execution")
                .contains("obs-authz-deny")
                .contains("obs-tool-success")
                .contains("zap_report_read");
    }

    @Test
    void policyDryRunPublishesExplainableDecisionAuditEvents() throws Exception {
        String sessionId = initializeSession("policy-api-key");

        callPolicyDryRun("policy-api-key", sessionId, "obs-policy-allow", "https://api.sandbox.example.com/orders")
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\\\"result\\\":\\\"allow\\\""));

        callPolicyDryRun("policy-api-key", sessionId, "obs-policy-deny", "https://prod.example.com/orders")
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\\\"result\\\":\\\"deny\\\""));

        EntityExchangeResult<String> auditEvents = actuator("observer-api-key", "/actuator/auditevents");
        JsonNode allowEvent = findAuditEvent(auditEvents.getResponseBody(), "policy_decision", "obs-policy-allow");
        JsonNode denyEvent = findAuditEvent(auditEvents.getResponseBody(), "policy_decision", "obs-policy-deny");

        assertThat(allowEvent.path("data").path("outcome").asText()).isEqualTo("allow");
        assertThat(allowEvent.path("data").path("bundleName").asText()).isEqualTo("policy-audit-preview");
        assertThat(allowEvent.path("data").path("bundleOwner").asText()).isEqualTo("security-platform");
        assertThat(allowEvent.path("data").path("evaluatedTool").asText()).isEqualTo("zap_attack_start");
        assertThat(allowEvent.path("data").path("normalizedHost").asText()).isEqualTo("api.sandbox.example.com");
        assertThat(allowEvent.path("data").path("decisionSource").asText()).isEqualTo("rule");
        assertThat(allowEvent.path("data").path("matchedRuleId").asText()).isEqualTo("allow-sandbox-attack");
        assertThat(allowEvent.path("data").path("reason").asText()).isEqualTo("sandbox rollout window");
        assertThat(allowEvent.path("data").path("validationValid").asBoolean()).isTrue();
        assertThat(allowEvent.path("data").path("traceSummary").isArray()).isTrue();
        assertThat(allowEvent.path("data").path("traceSummary")).isNotEmpty();

        assertThat(denyEvent.path("data").path("outcome").asText()).isEqualTo("deny");
        assertThat(denyEvent.path("data").path("decisionSource").asText()).isEqualTo("default");
        assertThat(denyEvent.path("data").path("defaultDecision").asText()).isEqualTo("deny");
        assertThat(denyEvent.path("data").path("reason").asText())
                .isEqualTo("No enabled rule matched the request. Using bundle default decision.");
        assertThat(denyEvent.path("data").path("matchedRuleId").isMissingNode()
                || denyEvent.path("data").path("matchedRuleId").isNull()).isTrue();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
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
                                "name", "observability-test",
                                "version", "1.0.0"
                        )
                )
        ));

        EntityExchangeResult<String> result = client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
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

    private WebTestClient.ResponseSpec callReportRead(String apiKey, String sessionId, String correlationId) throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 1,
                "params", Map.of(
                        "name", "zap_report_read",
                        "arguments", Map.of("reportPath", reportFile.toString(), "maxChars", 1000)
                )
        ));

        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header("X-Correlation-Id", correlationId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange();
    }

    private WebTestClient.ResponseSpec callPolicyDryRun(String apiKey,
                                                        String sessionId,
                                                        String correlationId,
                                                        String target) throws Exception {
        String bundleJson = OBJECT_MAPPER.writeValueAsString(Map.of(
                "apiVersion", "mcp.zap.policy/v1",
                "kind", "PolicyBundle",
                "metadata", Map.of(
                        "name", "policy-audit-preview",
                        "description", "Allow sandbox attack during staffed hours",
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
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 2,
                "params", Map.of(
                        "name", "zap_policy_dry_run",
                        "arguments", Map.of(
                                "policyBundle", bundleJson,
                                "toolName", "zap_attack_start",
                                "target", target,
                                "evaluatedAt", "2026-04-06T09:00:00Z"
                        )
                )
        ));

        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header("X-Correlation-Id", correlationId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange();
    }

    private EntityExchangeResult<String> actuator(String apiKey, String path) {
        return client().get()
                .uri(path)
                .header("X-API-Key", apiKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
    }

    private JsonNode findAuditEvent(String responseBody, String type, String correlationId) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        for (JsonNode event : root.path("events")) {
            if (type.equals(event.path("type").asText())
                    && correlationId.equals(event.path("data").path("correlationId").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing audit event type=" + type + " correlationId=" + correlationId);
    }
}
