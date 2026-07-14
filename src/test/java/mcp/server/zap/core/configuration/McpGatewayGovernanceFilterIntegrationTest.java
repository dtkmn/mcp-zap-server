package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.auth.apiKeys[0].clientId=governance-client",
                "mcp.server.auth.apiKeys[0].workspaceId=governance-workspace",
                "mcp.server.auth.apiKeys[0].key=governance-api-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.protection.enabled=false"
        }
)
@ActiveProfiles("test")
class McpGatewayGovernanceFilterIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void activeGovernanceRejectsInvalidJsonRpcRequestBeforeToolDispatchAndRecordsTelemetry() throws Exception {
        client().post()
                .uri("/mcp")
                .header("X-API-Key", "governance-api-key")
                .header("X-Correlation-Id", "invalid-governance-request")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"jsonrpc":"2.0","method":"tools/call","id":1,"params":{}}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("invalid_json_rpc_request")
                .jsonPath("$.reason").isEqualTo("invalid_request_shape")
                .jsonPath("$.correlationId").isEqualTo("invalid-governance-request");

        EntityExchangeResult<String> metric = actuator("/actuator/metrics/mcp.zap.invalid_mcp_requests");
        assertThat(metric.getResponseBody())
                .contains("\"name\":\"mcp.zap.invalid_mcp_requests\"")
                .contains("\"tag\":\"reason\"");

        JsonNode auditEvent = findAuditEvent(
                actuator("/actuator/auditevents").getResponseBody(),
                "invalid_mcp_request",
                "invalid-governance-request"
        );
        assertThat(auditEvent.path("data").path("reason").asText()).isEqualTo("invalid_request_shape");
        assertThat(auditEvent.path("principal").asText()).isEqualTo("anonymous");
    }

    @Test
    void activeGovernanceRejectsDuplicateJsonRpcFields() {
        assertInvalidRequest(
                """
                        {"jsonrpc":"2.0","method":"tools/list","method":"tools/call","id":1,"params":{"name":"zap_spider_scan"}}
                """,
                "duplicate-json-rpc-field",
                "invalid_json_rpc_request"
        );
    }

    @Test
    void activeGovernanceRejectsPaddedJsonRpcIdentifiers() {
        assertInvalidRequest(
                """
                        {"jsonrpc":"2.0","method":" tools/list ","id":1}
                """,
                "padded-json-rpc-method",
                "invalid_request_shape"
        );
        assertInvalidRequest(
                """
                        {"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":" zap_spider_scan "}}
                """,
                "padded-json-rpc-tool",
                "invalid_request_shape"
        );
    }

    private void assertInvalidRequest(String body, String correlationId, String reason) {
        client().post()
                .uri("/mcp")
                .header("X-API-Key", "governance-api-key")
                .header("X-Correlation-Id", correlationId)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("invalid_json_rpc_request")
                .jsonPath("$.reason").isEqualTo(reason)
                .jsonPath("$.correlationId").isEqualTo(correlationId);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private EntityExchangeResult<String> actuator(String path) {
        return client().get()
                .uri(path)
                .header("X-API-Key", "governance-api-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
    }

    private JsonNode findAuditEvent(String responseBody, String type, String correlationId) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);
        for (JsonNode event : root.path("events")) {
            if (type.equals(event.path("type").asText())
                    && correlationId.equals(event.path("data").path("correlationId").asText())) {
                return event;
            }
        }
        throw new AssertionError("Missing audit event type=" + type + " correlationId=" + correlationId);
    }
}
