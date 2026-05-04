package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.auth.apiKeys[0].clientId=runtime-policy-client",
                "mcp.server.auth.apiKeys[0].workspaceId=runtime-policy-workspace",
                "mcp.server.auth.apiKeys[0].key=runtime-policy-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:scan:attack:run",
                "mcp.server.protection.enabled=false",
                "mcp.server.policy.mode=enforce",
                "zap.scan.url.validation.enabled=false"
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
class McpRuntimePolicyEnforceIntegrationTest extends AbstractMcpRuntimePolicyIntegrationTest {

    @DynamicPropertySource
    static void registerPolicyBundle(DynamicPropertyRegistry registry) {
        registry.add("mcp.server.policy.bundle", () -> {
            try {
                return runtimePolicyBundleJson();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build test policy bundle", e);
            }
        });
    }

    @Test
    void enforceDenyFromMcpClientBlocksToolExecutionWithSafeExplainableError() throws Exception {
        String sessionId = initializeSession(POLICY_API_KEY);
        EntityExchangeResult<String> result = callAttackTool(
                POLICY_API_KEY,
                sessionId,
                "policy-enforce-client-deny",
                "https://example.com/orders?token=" + SECRET_TOKEN
        );

        String responseBody = normalizeResponseBody(result.getResponseBody());
        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode envelope = OBJECT_MAPPER.readTree(responseBody);
        String errorText = envelope.path("result").path("content").path(0).path("text").asText();
        assertThat(envelope.path("result").path("isError").asBoolean()).isTrue();
        assertThat(errorText)
                .contains(APPROVAL_REQUIRED_REASON)
                .doesNotContain(SECRET_TOKEN)
                .doesNotContain("orders?token");
        assertThat(responseBody).doesNotContain("Guided attack started");

        JsonNode event = findAuditEvent(
                actuator(POLICY_API_KEY, "/actuator/auditevents").getResponseBody(),
                "policy_decision",
                "policy-enforce-client-deny"
        );
        assertRuntimePolicyAudit(event, "deny", false);
        assertThat(event.path("data").path("mode").asText()).isEqualTo("enforce");

        EntityExchangeResult<String> auditMetric = actuator(POLICY_API_KEY, "/actuator/metrics/asg.audit.events");
        assertThat(auditMetric.getResponseBody())
                .contains("\"name\":\"asg.audit.events\"")
                .contains("\"tag\":\"type\"")
                .contains("\"tag\":\"outcome\"");

        EntityExchangeResult<String> prometheus = actuator(POLICY_API_KEY, "/actuator/prometheus");
        assertThat(prometheus.getResponseBody())
                .contains("asg_audit_events_total")
                .contains("type=\"policy_decision\"")
                .contains("outcome=\"deny\"");
        assertThat(prometheus.getResponseBody()).doesNotContain(SECRET_TOKEN);
    }
}
