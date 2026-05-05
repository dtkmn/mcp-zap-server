package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

abstract class AbstractMcpRuntimePolicyIntegrationTest extends AbstractMcpProtectionIntegrationTest {
    protected static final String POLICY_API_KEY = "runtime-policy-api-key";
    protected static final String APPROVAL_REQUIRED_REASON = "Active scans against example.com require approval.";
    protected static final String SECRET_TOKEN = "super-secret-token";

    protected EntityExchangeResult<String> callAttackTool(String apiKey,
                                                          String sessionId,
                                                          String correlationId,
                                                          String targetUrl) throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 42,
                "params", Map.of(
                        "name", "zap_attack_start",
                        "arguments", Map.of(
                                "targetUrl", targetUrl,
                                "recurse", "false",
                                "policy", "Default Policy"
                        )
                )
        ));

        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header("X-Correlation-Id", correlationId)
                .header("Accept", "application/json,text/event-stream")
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    protected EntityExchangeResult<String> actuator(String apiKey, String path) {
        return client().get()
                .uri(path)
                .header("X-API-Key", apiKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
    }

    protected JsonNode findAuditEvent(String responseBody, String type, String correlationId) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        for (JsonNode event : root.path("events")) {
            if (type.equals(event.path("type").asText())
                    && correlationId.equals(event.path("data").path("correlationId").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing audit event type=" + type + " correlationId=" + correlationId);
    }

    protected String normalizeResponseBody(String body) {
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

    protected static String runtimePolicyBundleJson() throws Exception {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "apiVersion", "mcp.zap.policy/v1",
                "kind", "PolicyBundle",
                "metadata", Map.of(
                        "name", "runtime-policy-client-proof",
                        "description", "Block active scans against example.com from real MCP calls.",
                        "owner", "security-platform"
                ),
                "spec", Map.of(
                        "defaultDecision", "allow",
                        "evaluationOrder", "first-match",
                        "timezone", "UTC",
                        "rules", List.of(Map.of(
                                "id", "deny-example-active-scan",
                                "description", "Require approval before active scans against example.com.",
                                "decision", "deny",
                                "reason", APPROVAL_REQUIRED_REASON,
                                "match", Map.of(
                                        "tools", List.of("zap_attack_start"),
                                        "hosts", List.of("example.com")
                                )
                        ))
                )
        ));
    }

    protected void assertRuntimePolicyAudit(JsonNode event, String expectedOutcome, boolean expectedAllowed) {
        JsonNode data = event.path("data");
        assertThat(data.path("outcome").asText()).isEqualTo(expectedOutcome);
        assertThat(data.path("mode").asText()).isIn("dry_run", "enforce");
        assertThat(data.path("allowed").asBoolean()).isEqualTo(expectedAllowed);
        assertThat(data.path("tool").asText()).isEqualTo("zap_attack_start");
        assertThat(data.path("targetProvided").asBoolean()).isTrue();
        assertThat(data.path("policyProvider").asText()).isEqualTo("basic_policy_bundle");
        assertThat(data.path("policyProviderCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(data.path("policyName").asText()).isEqualTo("runtime-policy-client-proof");
        assertThat(data.path("decisionResult").asText()).isEqualTo("deny");
        assertThat(data.path("decisionSource").asText()).isEqualTo("rule");
        assertThat(data.path("matchedRuleId").asText()).isEqualTo("deny-example-active-scan");
        assertThat(data.path("reason").asText()).isEqualTo(APPROVAL_REQUIRED_REASON);
        assertThat(data.path("normalizedHost").asText()).isEqualTo("example.com");
        assertThat(data.path("validationValid").asBoolean()).isTrue();
        assertThat(data.has("target")).isFalse();
        assertThat(data.toString()).doesNotContain(SECRET_TOKEN);
    }
}
