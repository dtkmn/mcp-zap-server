package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.auth.apiKeys[0].clientId=limited-client",
                "mcp.server.auth.apiKeys[0].key=limited-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.protection.enabled=true",
                "mcp.server.protection.rate-limit.enabled=true",
                "mcp.server.protection.rate-limit.capacity=3",
                "mcp.server.protection.rate-limit.refill-tokens=3",
                "mcp.server.protection.rate-limit.refill-period-seconds=3600",
                "mcp.server.protection.workspace-quota.enabled=false",
                "mcp.server.protection.backpressure.enabled=false"
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
class McpRateLimitIntegrationTest extends AbstractMcpProtectionIntegrationTest {

    @Test
    void repeatedRequestsReturnHttp429AfterClientBucketIsExhausted() throws Exception {
        String sessionId = initializeSession("limited-api-key");

        assertThat(listTools("limited-api-key", sessionId).getStatus().value()).isEqualTo(200);
        assertThat(listTools("limited-api-key", sessionId).getStatus().value()).isEqualTo(200);

        var rejected = listTools("limited-api-key", sessionId);
        assertThat(rejected.getStatus().value()).isEqualTo(429);
        String responseBody = rejected.getResponseBody();

        assertThat(responseBody).contains("rate_limited");
        assertThat(responseBody).contains("client_request_rate");
    }
}
