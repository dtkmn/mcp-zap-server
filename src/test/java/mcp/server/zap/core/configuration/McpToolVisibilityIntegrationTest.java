package mcp.server.zap.core.configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import mcp.server.zap.core.service.SpiderScanService;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import mcp.server.zap.core.service.protection.McpAbuseProtectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Consumer acceptance tests for issue #227. These deliberately exercise the real
 * tool provider and HTTP filter wiring, without assuming a future catalog API.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.tools.surface=guided",
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.security.authorization.allow-wildcard=true",
                "mcp.server.auth.apiKeys[0].clientId=visibility-lister",
                "mcp.server.auth.apiKeys[0].key=visibility-list-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[1].clientId=visibility-reader",
                "mcp.server.auth.apiKeys[1].key=visibility-read-key",
                "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[1].scopes[1]=zap:scan:read",
                "mcp.server.auth.apiKeys[2].clientId=visibility-admin",
                "mcp.server.auth.apiKeys[2].key=visibility-admin-key",
                "mcp.server.auth.apiKeys[2].scopes[0]=*",
                "mcp.server.protection.enabled=false",
                "mcp.server.protection.rate-limit.enabled=false"
        }
)
@ActiveProfiles("test")
@Execution(ExecutionMode.SAME_THREAD)
class McpToolVisibilityIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private static final String LIST_KEY = "visibility-list-key";
    private static final String UNKNOWN_TOOL = "nonexistent_visibility_test_tool";
    private static final String DISABLED_TOOL = "zap_spider_status";
    private static final String ACTIVE_TOOL = "zap_passive_scan_status";

    @LocalServerPort
    private int port;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private McpToolRegistry mcpActiveToolRegistry;

    @Autowired
    private ToolAuthorizationProperties authorizationProperties;

    @Autowired
    private AbuseProtectionProperties protectionProperties;

    @MockitoSpyBean
    private ToolAuthorizationService authorizationService;

    @MockitoSpyBean
    private McpAbuseProtectionService protectionService;

    @MockitoBean
    private EnginePassiveScanAccess passiveScanAccess;

    @MockitoBean
    private SpiderScanService spiderScanService;

    @BeforeEach
    void verifyFixtureAndStubEngine() {
        restoreGovernanceModes();
        List<String> activeNames = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .toList();
        assertThat(activeNames).contains(ACTIVE_TOOL).doesNotContain(UNKNOWN_TOOL, DISABLED_TOOL);
        assertThat(mcpActiveToolRegistry.names()).containsExactlyInAnyOrderElementsOf(activeNames);
        assertThat(authorizationService.mappedToolNames())
                .contains(ACTIVE_TOOL, DISABLED_TOOL)
                .doesNotContain(UNKNOWN_TOOL);
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenReturn(new PassiveScanSnapshot(0, 0, false));
    }

    @AfterEach
    void restoreGovernanceModes() {
        authorizationProperties.setMode(ToolAuthorizationProperties.Mode.ENFORCE);
        protectionProperties.setEnabled(false);
    }

    @ParameterizedTest(name = "{0}, protection={1}: unknown and disabled tools are indistinguishable")
    @MethodSource("governanceModes")
    void unknownAndDisabledToolsHaveTheSamePublicErrorBeforeGovernance(
            ToolAuthorizationProperties.Mode mode, boolean protectionEnabled) throws Exception {
        String sessionId = initializeSession(LIST_KEY);
        authorizationProperties.setMode(mode);
        protectionProperties.setEnabled(protectionEnabled);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> unknown = call(LIST_KEY, sessionId, UNKNOWN_TOOL, "visibility-request");
        EntityExchangeResult<String> disabled = call(LIST_KEY, sessionId, DISABLED_TOOL, "visibility-request");

        assertAll(
                () -> assertUnknownToolResponse(unknown, "visibility-request"),
                () -> assertUnknownToolResponse(disabled, "visibility-request"),
                () -> assertThat(responseEnvelope(unknown)).isEqualTo(responseEnvelope(disabled)),
                () -> verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class)),
                () -> verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class)),
                () -> verifyNoInteractions(passiveScanAccess, spiderScanService)
        );
    }

    @ParameterizedTest(name = "Disabled tool remains unavailable with credential {0}")
    @ValueSource(strings = {"visibility-read-key", "visibility-admin-key"})
    void grantingTheDisabledToolsScopeOrWildcardDoesNotMakeItAvailable(String apiKey) throws Exception {
        String sessionId = initializeSession(apiKey);
        List<String> grantedScopes = apiKey.equals("visibility-admin-key") ? List.of("*") : List.of("zap:scan:read");
        assertThat(authorizationService.authorizeToolCall(grantedScopes, DISABLED_TOOL).allowed()).isTrue();
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = call(apiKey, sessionId, DISABLED_TOOL, 42);

        assertAll(
                () -> assertUnknownToolResponse(result, 42),
                () -> verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class)),
                () -> verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class)),
                () -> verifyNoInteractions(passiveScanAccess, spiderScanService)
        );
    }

    @ParameterizedTest(name = "API-key unknown tool preserves JSON-RPC id {0}")
    @MethodSource("requestIds")
    void unknownToolPreservesTheOriginalRequestId(Object requestId) throws Exception {
        String sessionId = initializeSession(LIST_KEY);

        assertUnknownToolResponse(call(LIST_KEY, sessionId, UNKNOWN_TOOL, requestId), requestId);
    }

    @Test
    void disabledToolsAreNotAdvertisedThroughDiscovery() throws Exception {
        String sessionId = initializeSession(LIST_KEY);
        EntityExchangeResult<String> result = post(LIST_KEY, sessionId, Map.of(
                "jsonrpc", "2.0", "id", 8, "method", "tools/list"
        ));

        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode envelope = responseEnvelope(result);
        List<String> advertisedNames = new ArrayList<>();
        for (JsonNode tool : envelope.path("result").path("tools")) {
            advertisedNames.add(tool.path("name").asString());
        }
        assertThat(advertisedNames).contains(ACTIVE_TOOL).doesNotContain(DISABLED_TOOL, UNKNOWN_TOOL);
        assertThat(advertisedNames).containsExactlyInAnyOrderElementsOf(mcpActiveToolRegistry.names());
    }

    @Test
    void activeToolStillRequiresItsPermissionAndDoesNotExecuteWhenDenied() throws Exception {
        String sessionId = initializeSession(LIST_KEY);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = call(LIST_KEY, sessionId, ACTIVE_TOOL, 9);

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("insufficient_scope", "zap:scan:read");
        JsonNode envelope = responseEnvelope(result);
        assertThat(envelope.path("error").asString()).isEqualTo("insufficient_scope");
        verify(authorizationService, times(1)).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
    }

    @Test
    void activeToolExecutesOnceWithTheRequiredPermission() throws Exception {
        String apiKey = "visibility-read-key";
        String sessionId = initializeSession(apiKey);
        protectionProperties.setEnabled(true);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = call(apiKey, sessionId, ACTIVE_TOOL, "allowed-request");

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        JsonNode envelope = responseEnvelope(result);
        assertThat(envelope.path("id").asString()).isEqualTo("allowed-request");
        assertThat(envelope.path("result").path("isError").asBoolean()).isFalse();
        assertThat(result.getResponseBody()).contains("Passive scan status");
        verify(authorizationService, times(1)).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, times(1)).evaluate(any(GatewayToolExecutionContext.class));
        verify(passiveScanAccess, times(1)).loadPassiveScanSnapshot();
        verifyNoInteractions(spiderScanService);
    }

    @ParameterizedTest
    @ValueSource(strings = {UNKNOWN_TOOL, DISABLED_TOOL, ACTIVE_TOOL})
    void invalidCredentialsAreRejectedBeforeAnyToolPermissionOrExecution(String toolName) throws Exception {
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = call("invalid-visibility-key", null, toolName, 10);

        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseBody()).doesNotContain(toolName, "requiredScopes", "insufficient_scope");
        verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
    }

    @ParameterizedTest(name = "{0}, protection={1}: notifications never receive JSON-RPC replies")
    @MethodSource("governanceModes")
    void initializedNotificationHasNoResponseBodyOrToolAuthorization(
            ToolAuthorizationProperties.Mode mode, boolean protectionEnabled) throws Exception {
        String sessionId = initializeSession(LIST_KEY, false);
        authorizationProperties.setMode(mode);
        protectionProperties.setEnabled(protectionEnabled);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = post(LIST_KEY, sessionId, Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized"
        ));

        assertThat(result.getStatus().value()).isEqualTo(202);
        assertThat(result.getResponseBody()).isNullOrEmpty();
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
    }

    @ParameterizedTest
    @ValueSource(strings = {UNKNOWN_TOOL, DISABLED_TOOL, ACTIVE_TOOL})
    void toolCallWithoutRequestIdDoesNotExecuteOrReceiveAJsonRpcReply(String toolName) throws Exception {
        String sessionId = initializeSession("visibility-admin-key");
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = post("visibility-admin-key", sessionId, Map.of(
                "jsonrpc", "2.0", "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of())
        ));

        assertThat(result.getStatus().value()).isEqualTo(202);
        assertThat(result.getResponseBody()).isNullOrEmpty();
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
    }

    @ParameterizedTest
    @MethodSource("invalidRequestIds")
    void invalidToolCallIdIsRejectedBeforePermissionOrExecution(Object requestId) throws Exception {
        String sessionId = initializeSession("visibility-admin-key");
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "jsonrpc", "2.0", "method", "tools/call",
                "params", Map.of("name", ACTIVE_TOOL, "arguments", Map.of())
        ));
        payload.put("id", requestId);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);

        EntityExchangeResult<String> result = post("visibility-admin-key", sessionId, payload);

        assertThat(result.getStatus().value()).isEqualTo(400);
        assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse();
        assertThat(responseEnvelope(result)).isEqualTo(OBJECT_MAPPER.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}"));
        verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
    }

    private void assertUnknownToolResponse(EntityExchangeResult<String> result, Object requestId) throws Exception {
        JsonNode expected = OBJECT_MAPPER.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", requestId,
                "error", Map.of("code", -32602, "message", "Unknown tool")
        ));
        assertAll(
                () -> assertThat(result.getStatus().value()).isEqualTo(200),
                () -> assertThat(result.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON),
                () -> assertThat(result.getResponseHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE)).isFalse(),
                () -> assertThat(responseEnvelope(result)).isEqualTo(expected)
        );
    }

    private JsonNode responseEnvelope(EntityExchangeResult<String> result) throws Exception {
        String body = result.getResponseBody();
        assertThat(body).isNotBlank();
        MediaType contentType = result.getResponseHeaders().getContentType();
        if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            // The SDK may return SSE for dispatched calls; the adapter's own error
            // contract is asserted separately so framing never hides the actual error.
            List<String> messages = Arrays.stream(body.split("\\r?\\n\\r?\\n"))
                    .map(event -> event.lines()
                            .filter(line -> line.startsWith("data:"))
                            .map(line -> line.substring(5).stripLeading())
                            .collect(java.util.stream.Collectors.joining("\n")))
                    .filter(data -> !data.isBlank())
                    .toList();
            assertThat(messages).hasSize(1);
            return OBJECT_MAPPER.readTree(messages.get(0));
        }
        return OBJECT_MAPPER.readTree(body);
    }

    private String initializeSession(String apiKey) throws Exception {
        return initializeSession(apiKey, true);
    }

    private String initializeSession(String apiKey, boolean sendInitializedNotification) throws Exception {
        EntityExchangeResult<String> result = post(apiKey, null, Map.of(
                "jsonrpc", "2.0", "id", 0, "method", "initialize",
                "params", Map.of(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "tool-visibility-regression", "version", "1.0.0")
                )
        ));
        assertThat(result.getStatus().value()).isEqualTo(200);
        String sessionId = result.getResponseHeaders().getFirst("Mcp-Session-Id");
        assertThat(sessionId).isNotBlank();
        if (sendInitializedNotification) {
            EntityExchangeResult<String> notification = post(apiKey, sessionId, Map.of(
                    "jsonrpc", "2.0", "method", "notifications/initialized"
            ));
            assertThat(notification.getStatus().value()).isEqualTo(202);
            assertThat(notification.getResponseBody()).isNullOrEmpty();
        }
        return sessionId;
    }

    private EntityExchangeResult<String> call(String apiKey, String sessionId, String toolName, Object requestId)
            throws Exception {
        return post(apiKey, sessionId, Map.of(
                "jsonrpc", "2.0", "id", requestId, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments",
                        DISABLED_TOOL.equals(toolName) ? Map.of("scanId", "1") : Map.of())
        ));
    }

    private EntityExchangeResult<String> post(String apiKey, String sessionId, Map<String, Object> payload)
            throws Exception {
        WebTestClient.RequestBodySpec request = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build()
                .post().uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("X-Correlation-Id", "tool-visibility-regression")
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + "," + MediaType.TEXT_EVENT_STREAM_VALUE)
                .contentType(MediaType.APPLICATION_JSON);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId).header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        return request.bodyValue(OBJECT_MAPPER.writeValueAsString(payload))
                .exchange().expectBody(String.class).returnResult();
    }

    static Stream<Arguments> governanceModes() {
        return Arrays.stream(ToolAuthorizationProperties.Mode.values())
                .flatMap(mode -> Stream.of(Arguments.of(mode, false), Arguments.of(mode, true)));
    }

    static Stream<Object> requestIds() {
        return Stream.of("request-α-42", 9007199254740993L);
    }

    static Stream<Arguments> invalidRequestIds() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(1.5), Arguments.of(true),
                Arguments.of(Map.of("value", 1)), Arguments.of(List.of(1)));
    }
}
