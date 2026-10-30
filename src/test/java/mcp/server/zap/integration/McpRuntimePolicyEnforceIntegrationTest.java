package mcp.server.zap.integration;

import tools.jackson.databind.JsonNode;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.zaproxy.clientapi.core.ClientApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    @Autowired
    private ScanJobStore scanJobStore;

    @Autowired
    private ClientApi zap;

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
        assertThat(scanJobStore.listStrict()).as("Policy fixture starts with an empty job store").isEmpty();
        EntityExchangeResult<String> result = callAttackTool(
                POLICY_API_KEY,
                sessionId,
                "policy-enforce-client-deny",
                "https://example.com/orders?token=" + SECRET_TOKEN
        );

        String responseBody = normalizeResponseBody(result.getResponseBody());
        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode envelope = OBJECT_MAPPER.readTree(responseBody);
        String errorText = envelope.path("result").path("content").path(0).path("text").asString();
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
        assertThat(event.path("data").path("mode").asString()).isEqualTo("enforce");

        EntityExchangeResult<String> auditMetric = actuator(POLICY_API_KEY, "/actuator/metrics/mcp.zap.audit.events");
        assertThat(auditMetric.getResponseBody())
                .contains("\"name\":\"mcp.zap.audit.events\"")
                .contains("\"tag\":\"type\"")
                .contains("\"tag\":\"outcome\"");

        EntityExchangeResult<String> prometheus = actuator(POLICY_API_KEY, "/actuator/prometheus");
        assertThat(prometheus.getResponseBody())
                .contains("mcp_zap_audit_events_total")
                .contains("type=\"policy_decision\"")
                .contains("outcome=\"deny\"");
        assertThat(prometheus.getResponseBody()).doesNotContain(SECRET_TOKEN);

        assertThat(scanJobStore.listStrict()).as("Policy-denied request must not admit a scan job").isEmpty();
        verify(zap.ascan, never()).scan(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class));
        verify(zap.ascan, never()).scanAsUser(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }
}
