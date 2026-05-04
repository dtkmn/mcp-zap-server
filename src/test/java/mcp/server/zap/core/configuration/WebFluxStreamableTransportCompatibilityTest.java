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

import static org.assertj.core.api.Assertions.assertThat;

class WebFluxStreamableTransportCompatibilityTest {

    @Test
    void listResponsesWithoutSseIdsDoNotFailAfterInitializeOnSpringFramework7() {
        WebFluxStreamableServerTransportProvider provider = WebFluxStreamableServerTransportProvider.builder()
                .messageEndpoint("/mcp")
                .build();
        provider.setSessionFactory(initializeRequest -> {
            McpRequestHandler<McpSchema.ListToolsResult> toolsListHandler = (exchange, params) -> Mono.just(
                    new McpSchema.ListToolsResult(List.of(McpSchema.Tool.builder()
                            .name("zap_test_tool")
                            .description("Compatibility test tool")
                            .build()), null));
            McpRequestHandler<McpSchema.ListPromptsResult> promptsListHandler = (exchange, params) -> Mono.just(
                    new McpSchema.ListPromptsResult(List.of(new McpSchema.Prompt(
                            "zap_test_prompt",
                            "Compatibility test prompt",
                            List.of())), null));
            McpRequestHandler<McpSchema.ListResourcesResult> resourcesListHandler = (exchange, params) -> Mono.just(
                    new McpSchema.ListResourcesResult(List.of(McpSchema.Resource.builder()
                            .uri("zap://test/resource")
                            .name("zap_test_resource")
                            .description("Compatibility test resource")
                            .mimeType(MediaType.TEXT_PLAIN_VALUE)
                            .build()), null));
            McpStreamableServerSession session = new McpStreamableServerSession(
                    "test-session",
                    initializeRequest.capabilities(),
                    initializeRequest.clientInfo(),
                    Duration.ofSeconds(5),
                    Map.of(
                            McpSchema.METHOD_TOOLS_LIST, toolsListHandler,
                            McpSchema.METHOD_PROMPT_LIST, promptsListHandler,
                            McpSchema.METHOD_RESOURCES_LIST, resourcesListHandler),
                    Map.of());
            McpSchema.InitializeResult initializeResult = new McpSchema.InitializeResult(
                    ProtocolVersions.MCP_2025_06_18,
                    McpSchema.ServerCapabilities.builder()
                            .tools(false)
                            .prompts(false)
                            .resources(false, false)
                            .build(),
                    new McpSchema.Implementation("test-server", "1.0"),
                    null);
            return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(initializeResult));
        });

        WebTestClient client = WebTestClient.bindToRouterFunction(provider.getRouterFunction()).build();

        String sessionId = initialize(client);

        expectListResponse(client, sessionId, 2, "tools/list", "zap_test_tool");
        expectListResponse(client, sessionId, 3, "prompts/list", "zap_test_prompt");
        expectListResponse(client, sessionId, 4, "resources/list", "zap://test/resource");
    }

    private static String initialize(WebTestClient client) {
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

        assertThat(sessionId).isNotBlank();
        return sessionId;
    }

    private static void expectListResponse(
            WebTestClient client, String sessionId, int id, String method, String expectedContent) {
        client.post()
                .uri("/mcp")
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Mcp-Session-Id", sessionId)
                .bodyValue("""
                        {"jsonrpc":"2.0","id":%d,"method":"%s","params":{}}
                        """.formatted(id, method))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("event:message")
                        .contains(expectedContent)
                        .doesNotContain("id:null"));
    }
}
