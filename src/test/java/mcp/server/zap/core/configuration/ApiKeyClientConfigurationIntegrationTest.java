package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.server.security.enabled=true",
        "mcp.server.security.mode=api-key",
        "mcp.server.tools.surface=expert",
        "mcp.server.auth.jwt.enabled=true",
        "mcp.server.auth.jwt.secret=registered-clients-integration-jwt-secret-at-least-32-characters",
        "mcp.server.auth.apiKeys[0].clientId=lister-client",
        "mcp.server.auth.apiKeys[0].key=lister-key",
        "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
        "mcp.server.auth.apiKeys[1].clientId=reporter-client",
        "mcp.server.auth.apiKeys[1].key=reporter-key",
        "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
        "mcp.server.auth.apiKeys[1].scopes[1]=zap:report:read",
        "zap.server.apiKey=integration-zap-key",
        "zap.server.url=localhost",
        "zap.server.port=65534",
        "zap.initialization.connectionTimeoutInSecs=1"
})
class ApiKeyClientConfigurationIntegrationTest {
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":0,"method":"initialize","params":{
            "protocolVersion":"2025-03-26","capabilities":{},
            "clientInfo":{"name":"registered-key-test","version":"1.0"}}}
            """;

    @LocalServerPort
    private int port;

    @Test
    void shippedPlaceholderIsNotAnAdditionalCredentialForCustomClients() {
        client().post().uri("/mcp")
                .header("X-API-Key", "changeme-default-key")
                .header(HttpHeaders.ACCEPT, acceptTypes())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(INITIALIZE)
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "API-Key");
    }

    @Test
    void registeredClientsCanInitializeAndDiscoverTools() {
        for (String key : new String[]{"lister-key", "reporter-key"}) {
            String sessionId = initialize(key);
            client().post().uri("/mcp")
                    .header("X-API-Key", key).header("Mcp-Session-Id", sessionId)
                    .header(HttpHeaders.ACCEPT, acceptTypes())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                    .exchange().expectStatus().isOk().expectBody(String.class)
                    .value(body -> assertThat(body).contains("\"tools\""));
        }
    }

    @Test
    void restrictedClientCannotAcquireUnconfiguredToolPermissions() {
        String sessionId = initialize("lister-key");
        client().post().uri("/mcp")
                .header("X-API-Key", "lister-key").header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, acceptTypes())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                        "params":{"name":"zap_report_read","arguments":{"reportPath":"unread-report.json"}}}
                        """)
                .exchange().expectStatus().isForbidden().expectBody()
                .jsonPath("$.error").isEqualTo("insufficient_scope");
    }

    @Test
    void tokenIssuanceUsesTheSameRegisteredClientsAndScopes() {
        client().post().uri("/auth/token").header("X-API-Key", "lister-key")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.clientId").isEqualTo("lister-client")
                .jsonPath("$.scopes[0]").isEqualTo("mcp:tools:list")
                .jsonPath("$.scopes.length()").isEqualTo(1);
        client().post().uri("/auth/token").header("X-API-Key", "changeme-default-key")
                .exchange().expectStatus().isUnauthorized();
    }

    private String initialize(String key) {
        EntityExchangeResult<String> response = client().post().uri("/mcp")
                .header("X-API-Key", key).header(HttpHeaders.ACCEPT, acceptTypes())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(INITIALIZE)
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        String sessionId = response.getResponseHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        client().post().uri("/mcp").header("X-API-Key", key).header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, acceptTypes()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .exchange().expectStatus().isAccepted();
        return sessionId;
    }

    private String acceptTypes() {
        return MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE;
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }
}
