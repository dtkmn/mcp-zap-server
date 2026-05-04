package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "mcp.server.auth.apiKeys[2].scopes[0]=zap:report:read"
        }
)
@ActiveProfiles("test")
class McpToolAuthorizationJwtIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Path reportFile;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createReportFile() throws Exception {
        reportFile = Files.createTempFile("mcp-authz-report", ".txt");
        Files.writeString(reportFile, "jwt integration report");
    }

    @DynamicPropertySource
    static void registerReportDirectory(DynamicPropertyRegistry registry) {
        registry.add("zap.report.directory", () -> reportFile.getParent().toString());
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

    @Test
    void toolsListReturnsCurrentRegisteredSurface() throws Exception {
        String token = issueAccessToken("reporter-api-key");
        String sessionId = initializeSession(token);

        client().post()
                .uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("zap_active_scan_start");
                    assertThat(body).contains("zap_findings_diff");
                    assertThat(body).contains("zap_automation_plan_run");
                    assertThat(body).contains("zap_queue_active_scan");
                });
    }

    @Test
    void toolCallReturns403WhenJwtLacksToolScope() throws Exception {
        String token = issueAccessToken("lister-api-key");
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
}
