package mcp.server.zap.core.configuration;

import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class WebFluxStreamableTransportCompatibilityTest {

    @Test
    void toolsListResponseWithoutSseIdDoesNotFailOnSpringFramework7() {
        WebFluxStreamableServerTransportProvider provider = WebFluxStreamableServerTransportProvider.builder()
                .messageEndpoint("/mcp")
                .build();
        provider.setSessionFactory(initializeRequest -> {
            McpRequestHandler<McpSchema.ListToolsResult> toolsListHandler = (exchange, params) -> Mono.just(
                    new McpSchema.ListToolsResult(List.of(McpSchema.Tool.builder()
                            .name("zap_test_tool")
                            .description("Compatibility test tool")
                            .build()), null));
            McpStreamableServerSession session = new McpStreamableServerSession(
                    "test-session",
                    initializeRequest.capabilities(),
                    initializeRequest.clientInfo(),
                    Duration.ofSeconds(5),
                    Map.of(McpSchema.METHOD_TOOLS_LIST, toolsListHandler),
                    Map.of());
            McpSchema.InitializeResult initializeResult = new McpSchema.InitializeResult(
                    ProtocolVersions.MCP_2025_06_18,
                    McpSchema.ServerCapabilities.builder().tools(false).build(),
                    new McpSchema.Implementation("test-server", "1.0"),
                    null);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(initializeResult));
        });

        WebTestClient client = WebTestClient.bindToRouterFunction(provider.getRouterFunction()).build();

        String sessionId = client.post()
                .uri("/mcp")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("Mcp-Session-Id")
                .returnResult(String.class)
                .getResponseHeaders()
                .getFirst("Mcp-Session-Id");

        client.post()
                .uri("/mcp")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Mcp-Session-Id", sessionId)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("zap_test_tool"));
    }
}
