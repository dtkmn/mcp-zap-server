package mcp.server.zap.integration;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.times;
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
                "mcp.server.policy.mode=dry-run",
                "zap.scan.url.validation.enabled=false"
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
class McpRuntimePolicyDryRunIntegrationTest extends AbstractMcpRuntimePolicyIntegrationTest {

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
    void dryRunDenyFromMcpClientRecordsPolicyAuditButAllowsToolExecution() throws Exception {
        String sessionId = initializeSession(POLICY_API_KEY);
        String targetUrl = "https://example.com/orders?token=" + SECRET_TOKEN;
        EntityExchangeResult<String> result = callAttackTool(
                POLICY_API_KEY,
                sessionId,
                "policy-dry-run-client-deny",
                targetUrl
        );

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(normalizeResponseBody(result.getResponseBody()))
                .contains("Guided attack started")
                .contains("Scan ID: scan-");

        JsonNode event = findAuditEvent(
                actuator(POLICY_API_KEY, "/actuator/auditevents").getResponseBody(),
                "policy_decision",
                "policy-dry-run-client-deny"
        );
        assertRuntimePolicyAudit(event, "dry_run_deny", false);
        assertThat(event.path("data").path("mode").asString()).isEqualTo("dry_run");

        EntityExchangeResult<String> auditMetric = actuator(POLICY_API_KEY, "/actuator/metrics/mcp.zap.audit.events");
        assertThat(auditMetric.getResponseBody())
                .contains("\"name\":\"mcp.zap.audit.events\"")
                .contains("\"tag\":\"type\"")
                .contains("\"tag\":\"outcome\"");

        EntityExchangeResult<String> prometheus = actuator(POLICY_API_KEY, "/actuator/prometheus");
        assertThat(prometheus.getResponseBody())
                .contains("mcp_zap_audit_events_total")
                .contains("type=\"policy_decision\"")
                .contains("outcome=\"dry_run_deny\"");
        assertThat(prometheus.getResponseBody()).doesNotContain(SECRET_TOKEN);

        ArgumentCaptor<String> startedTarget = ArgumentCaptor.forClass(String.class);
        verify(zap.ascan, times(1)).scan(startedTarget.capture(), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class));
        assertThat(startedTarget.getValue()).isEqualTo(targetUrl);
        verify(zap.ascan, never()).scanAsUser(nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }
}
