package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Ascan;
import org.zaproxy.clientapi.gen.Network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.nullable;

public abstract class AbstractMcpProtectionIntegrationTest {
    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    protected WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    protected String initializeSession(String apiKey) throws Exception {
        String initializeRequest = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 0,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "protection-test",
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

    protected EntityExchangeResult<String> listTools(String apiKey, String sessionId) {
        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}")
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    protected EntityExchangeResult<String> callTool(String apiKey,
                                                    String sessionId,
                                                    String toolName,
                                                    Map<String, Object> arguments) throws Exception {
        String request = OBJECT_MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "tools/call",
                "id", 1,
                "params", Map.of(
                        "name", toolName,
                        "arguments", arguments
                )
        ));

        return client().post()
                .uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("Mcp-Session-Id", sessionId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectBody(String.class)
                .returnResult();
    }

    @TestConfiguration
    static class MockZapConfig {
        @Bean
        @Primary
        ClientApi mockZapClientApi() throws Exception {
            ClientApi clientApi = new ClientApi("localhost", 0);

            Network network = mock(Network.class);
            clientApi.network = network;

            Ascan ascan = mock(Ascan.class);
            AtomicInteger scanCounter = new AtomicInteger(0);
            when(ascan.scan(
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class)))
                    .thenAnswer(invocation -> new ApiResponseElement("scan", "scan-" + scanCounter.incrementAndGet()));
            when(ascan.scanAsUser(
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class),
                    nullable(String.class)))
                    .thenAnswer(invocation -> new ApiResponseElement("scan", "scan-user-" + scanCounter.incrementAndGet()));
            when(ascan.status(nullable(String.class)))
                    .thenReturn(new ApiResponseElement("status", "0"));
            clientApi.ascan = ascan;

            return clientApi;
        }
    }
}
