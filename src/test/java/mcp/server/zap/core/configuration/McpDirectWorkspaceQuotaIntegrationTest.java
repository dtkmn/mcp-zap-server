package mcp.server.zap.core.configuration;

import java.util.Map;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
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
                "mcp.server.auth.apiKeys[1].clientId=client-b",
                "mcp.server.auth.apiKeys[1].key=client-b-key",
                "mcp.server.auth.apiKeys[1].workspaceId=workspace-one",
                "mcp.server.auth.apiKeys[1].scopes[0]=zap:scan:active:run",
                "mcp.server.protection.enabled=true",
                "mcp.server.protection.rate-limit.enabled=false",
                "mcp.server.protection.workspace-quota.enabled=true",
                "mcp.server.protection.workspace-quota.max-direct-scans=1",
                "mcp.server.protection.backpressure.enabled=false"
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
class McpDirectWorkspaceQuotaIntegrationTest extends AbstractMcpProtectionIntegrationTest {
    @Autowired
    private OperationRegistry operationRegistry;

    @Test
    void directAdmissionIsRejectedWhenWorkspaceAlreadyHasItsQuotaOfDirectScans() throws Exception {
        String sessionA = initializeSession("client-a-key");
        String sessionB = initializeSession("client-b-key");

        EntityExchangeResult<String> first = callTool(
                "client-a-key",
                sessionA,
                "zap_active_scan_start",
                Map.of("targetUrl", "https://example.com")
        );
        assertThat(first.getStatus().value()).isEqualTo(200);
        assertThat(first.getResponseBody()).contains("Direct active scan");
        assertThat(operationRegistry.countDirectScans("workspace-one")).isEqualTo(1);

        EntityExchangeResult<String> second = callTool(
                "client-b-key",
                sessionB,
                "zap_active_scan_start",
                Map.of("targetUrl", "https://example.com")
        );
        assertThat(second.getStatus().value()).isEqualTo(429);
        assertThat(second.getResponseBody()).contains("workspace_quota_exceeded");
        assertThat(second.getResponseBody()).contains("workspace_direct_scans");
        assertThat(second.getResponseBody()).contains("workspace-one");
    }
}
