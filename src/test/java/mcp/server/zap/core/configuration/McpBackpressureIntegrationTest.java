package mcp.server.zap.core.configuration;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.apiKeys[0].clientId=client-a",
                "mcp.server.auth.apiKeys[0].key=client-a-key",
                "mcp.server.auth.apiKeys[0].workspaceId=workspace-one",
                "mcp.server.auth.apiKeys[0].scopes[0]=zap:scan:active:run",
                "mcp.server.auth.apiKeys[1].clientId=client-c",
                "mcp.server.auth.apiKeys[1].key=client-c-key",
                "mcp.server.auth.apiKeys[1].workspaceId=workspace-two",
                "mcp.server.auth.apiKeys[1].scopes[0]=zap:scan:active:run",
                "mcp.server.protection.enabled=true",
                "mcp.server.protection.rate-limit.enabled=false",
                "mcp.server.protection.workspace-quota.enabled=false",
                "mcp.server.protection.backpressure.enabled=true",
                "mcp.server.protection.backpressure.max-tracked-scan-jobs=1",
                "mcp.server.protection.backpressure.max-running-scan-jobs=10"
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
class McpBackpressureIntegrationTest extends AbstractMcpProtectionIntegrationTest {

    @Test
    void queueAdmissionReturnsHttp429WhenGlobalBackpressureThresholdIsReached() throws Exception {
        String sessionA = initializeSession("client-a-key");
        String sessionC = initializeSession("client-c-key");

        EntityExchangeResult<String> first = callTool(
                "client-a-key",
                sessionA,
                "zap_queue_active_scan",
                Map.of("targetUrl", "https://example.com")
        );
        assertThat(first.getStatus().value()).isEqualTo(200);

        EntityExchangeResult<String> second = callTool(
                "client-c-key",
                sessionC,
                "zap_queue_active_scan",
                Map.of("targetUrl", "https://example.com")
        );
        assertThat(second.getStatus().value()).isEqualTo(429);
        assertThat(second.getResponseBody()).contains("overloaded");
        assertThat(second.getResponseBody()).contains("scan_job_backlog");
    }
}
