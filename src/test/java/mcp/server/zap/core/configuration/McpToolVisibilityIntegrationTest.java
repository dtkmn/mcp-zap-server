package mcp.server.zap.core.configuration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Consumer acceptance tests for issues #227 and #259, using initialized MCP
 * sessions against the configured server and its real tool provider.
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
                "mcp.server.auth.apiKeys[0].workspaceId=visibility-list-workspace",
                "mcp.server.auth.apiKeys[0].key=visibility-list-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[1].clientId=visibility-reader",
                "mcp.server.auth.apiKeys[1].workspaceId=visibility-read-workspace",
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
    private AuditEventRepository auditEventRepository;

    @Autowired
    private MeterRegistry meterRegistry;

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

        String unknownCorrelation = "unknown-" + mode + "-" + protectionEnabled;
        String disabledCorrelation = "disabled-" + mode + "-" + protectionEnabled;
        double previousRejections = counterCount("mcp.zap.adapter.rejections", "reason", "unknown_tool");
        EntityExchangeResult<String> unknown = call(LIST_KEY, sessionId, UNKNOWN_TOOL, "visibility-request", unknownCorrelation);
        EntityExchangeResult<String> disabled = call(LIST_KEY, sessionId, DISABLED_TOOL, "visibility-request", disabledCorrelation);

        assertAll(
                () -> assertUnknownToolResponse(unknown, "visibility-request"),
                () -> assertUnknownToolResponse(disabled, "visibility-request"),
                () -> assertThat(responseEnvelope(unknown)).isEqualTo(responseEnvelope(disabled)),
                () -> verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class)),
                () -> verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class)),
                () -> verifyNoInteractions(passiveScanAccess, spiderScanService)
        );
        assertAdapterRejectionAudit(unknownCorrelation, "unknown_tool");
        assertAdapterRejectionAudit(disabledCorrelation, "unknown_tool");
        assertThat(counterCount("mcp.zap.adapter.rejections", "reason", "unknown_tool"))
                .isEqualTo(previousRejections + 2);
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
        String correlationId = "helper-permission-denied";
        double previousDecisions = authorizationCount("denied", "insufficient_scope");
        long previousExecutions = successfulExecutionCount();

        EntityExchangeResult<String> result = call(LIST_KEY, sessionId, ACTIVE_TOOL, 9, correlationId);

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .contains("insufficient_scope", "zap:scan:read");
        JsonNode envelope = responseEnvelope(result);
        assertThat(envelope.path("error").asString()).isEqualTo("insufficient_scope");
        verify(authorizationService, times(1)).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
        assertAuthorizationAudit(correlationId, "visibility-lister", "visibility-list-workspace",
                "denied", "insufficient_scope", List.of("mcp:tools:list"));
        assertThat(authorizationCount("denied", "insufficient_scope")).isEqualTo(previousDecisions + 1);
        assertThat(successfulExecutionCount()).isEqualTo(previousExecutions);
        assertThat(audits(correlationId, "tool_execution")).isEmpty();
        assertAuthorizationContext("visibility-lister", "visibility-list-workspace", correlationId);
    }

    @Test
    void activeToolExecutesOnceWithTheRequiredPermission() throws Exception {
        String apiKey = "visibility-read-key";
        String sessionId = initializeSession(apiKey);
        protectionProperties.setEnabled(true);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);
        String correlationId = "helper-permission-allowed";
        double previousDecisions = authorizationCount("allowed", "scope_granted");
        long previousExecutions = successfulExecutionCount();

        EntityExchangeResult<String> result = call(apiKey, sessionId, ACTIVE_TOOL, "allowed-request", correlationId);

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
        assertAuthorizationAudit(correlationId, "visibility-reader", "visibility-read-workspace",
                "allowed", "scope_granted", List.of("mcp:tools:list", "zap:scan:read"));
        assertThat(authorizationCount("allowed", "scope_granted")).isEqualTo(previousDecisions + 1);
        assertSuccessfulToolExecution(correlationId, previousExecutions);
        GatewayToolExecutionContext authorizationContext = assertAuthorizationContext(
                "visibility-reader", "visibility-read-workspace", correlationId);
        ArgumentCaptor<GatewayToolExecutionContext> protectionContext = ArgumentCaptor.forClass(GatewayToolExecutionContext.class);
        verify(protectionService).evaluate(protectionContext.capture());
        assertThat(protectionContext.getValue()).isSameAs(authorizationContext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WARN", "OFF"})
    void warningAndDisabledAuthorizationRetainExecutionAndDomainObservability(String mode) throws Exception {
        String sessionId = initializeSession(LIST_KEY);
        authorizationProperties.setMode(ToolAuthorizationProperties.Mode.valueOf(mode));
        protectionProperties.setEnabled(true);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);
        String correlationId = "helper-permission-" + mode;
        double previousWarnings = authorizationCount("warn", "insufficient_scope");
        long previousExecutions = successfulExecutionCount();

        EntityExchangeResult<String> result = call(LIST_KEY, sessionId, ACTIVE_TOOL, "mode-request", correlationId);

        assertThat(result.getStatus().value()).isEqualTo(200);
        assertThat(responseEnvelope(result).path("result").path("isError").asBoolean()).isFalse();
        verify(protectionService).evaluate(any(GatewayToolExecutionContext.class));
        verify(passiveScanAccess).loadPassiveScanSnapshot();
        verifyNoInteractions(spiderScanService);
        assertSuccessfulToolExecution(correlationId, previousExecutions);
        if ("WARN".equals(mode)) {
            assertAuthorizationAudit(correlationId, "visibility-lister", "visibility-list-workspace",
                    "warn", "insufficient_scope", List.of("mcp:tools:list"));
            assertThat(authorizationCount("warn", "insufficient_scope")).isEqualTo(previousWarnings + 1);
            assertAuthorizationContext("visibility-lister", "visibility-list-workspace", correlationId);
        } else {
            verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
            assertThat(governanceAudits(correlationId)).isEmpty();
            assertThat(authorizationCount("warn", "insufficient_scope")).isEqualTo(previousWarnings);
        }
    }

    @Test
    void protectionRejectionPublishesOneSignalAfterAuthorizationAndPreservesMetrics() throws Exception {
        String sessionId = initializeSession("visibility-read-key");
        protectionProperties.setEnabled(true);
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);
        String correlationId = "helper-protection-rejection";
        doReturn(McpAbuseProtectionDecision.reject("rate_limited", "client_request_rate", ACTIVE_TOOL,
                "visibility-reader", "visibility-read-workspace", 37))
                .when(protectionService).evaluate(any(GatewayToolExecutionContext.class));
        double previousRejections = counterCount("mcp.zap.protection.rejections",
                "error", "rate_limited", "reason", "client_request_rate");

        EntityExchangeResult<String> result = call("visibility-read-key", sessionId, ACTIVE_TOOL, 37, correlationId);

        assertThat(result.getStatus().value()).isEqualTo(429);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(responseEnvelope(result).path("retryAfterSeconds").asLong()).isEqualTo(37);
        verifyNoInteractions(passiveScanAccess, spiderScanService);
        assertThat(governanceAudits(correlationId)).extracting(AuditEvent::getType)
                .containsExactlyInAnyOrder("authorization", "protection_rejection");
        List<AuditEvent> rejections = audits(correlationId, "protection_rejection");
        assertThat(rejections).hasSize(1);
        assertThat(rejections.getFirst().getPrincipal()).isEqualTo("visibility-reader");
        assertThat(rejections.getFirst().getData()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "tool", ACTIVE_TOOL, "errorCode", "rate_limited", "reason", "client_request_rate",
                "retryAfterSeconds", 37L, "workspaceId", "visibility-read-workspace",
                "correlationId", correlationId, "outcome", "rejected"));
        assertThat(counterCount("mcp.zap.protection.rejections",
                "error", "rate_limited", "reason", "client_request_rate")).isEqualTo(previousRejections + 1);
        assertThat(audits(correlationId, "tool_execution")).isEmpty();
    }

    @Test
    void invalidRequestPublishesOneSparseDiagnosticSignalBeforeGovernanceOrExecution() throws Exception {
        String sessionId = initializeSession("visibility-read-key");
        clearInvocations(authorizationService, protectionService, passiveScanAccess, spiderScanService);
        String correlationId = "helper-invalid-request";
        double previousInvalidRequests = counterCount("mcp.zap.invalid_mcp_requests",
                "reason", "invalid_request_shape");

        EntityExchangeResult<String> result = post("visibility-read-key", sessionId, Map.of(
                "jsonrpc", "2.0", "id", 91, "method", "tools/call", "params", Map.of()
        ), correlationId);

        assertThat(result.getStatus().value()).isEqualTo(400);
        assertThat(responseEnvelope(result).path("reason").asString()).isEqualTo("invalid_request_shape");
        verify(authorizationService, never()).authorize(anyCollection(), any(GatewayToolExecutionContext.class));
        verify(protectionService, never()).evaluate(any(GatewayToolExecutionContext.class));
        verifyNoInteractions(passiveScanAccess, spiderScanService);
        assertDiagnosticAudit("invalid_mcp_request", correlationId, "invalid_request_shape");
        assertThat(counterCount("mcp.zap.invalid_mcp_requests", "reason", "invalid_request_shape"))
                .isEqualTo(previousInvalidRequests + 1);
        assertThat(audits(correlationId, "tool_execution")).isEmpty();
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

    private void assertAuthorizationAudit(String correlationId, String clientId, String workspaceId,
                                          String outcome, String reason, List<String> grantedScopes) {
        List<AuditEvent> events = governanceAudits(correlationId);
        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.getType()).isEqualTo("authorization");
        assertThat(event.getPrincipal()).isEqualTo(clientId);
        assertThat(event.getData()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "action", ACTIVE_TOOL, "reason", reason,
                "requiredScopes", List.of("zap:scan:read"), "grantedScopes", grantedScopes,
                "workspaceId", workspaceId, "correlationId", correlationId, "outcome", outcome));
    }

    private void assertAdapterRejectionAudit(String correlationId, String reason) {
        assertDiagnosticAudit("adapter_rejection", correlationId, reason);
    }

    private void assertDiagnosticAudit(String type, String correlationId, String reason) {
        List<AuditEvent> events = governanceAudits(correlationId);
        assertThat(events).hasSize(1);
        AuditEvent event = events.getFirst();
        assertThat(event.getType()).isEqualTo(type);
        assertThat(event.getPrincipal()).isEqualTo("anonymous");
        assertThat(event.getData()).containsOnlyKeys("reason", "requestId", "correlationId", "outcome")
                .containsEntry("reason", reason).containsEntry("correlationId", correlationId)
                .containsEntry("outcome", "rejected");
        assertThat(event.getData().get("requestId")).isInstanceOf(String.class);
        assertThat((String) event.getData().get("requestId")).isNotBlank();
    }

    private List<AuditEvent> governanceAudits(String correlationId) {
        return auditEventRepository.find(null, Instant.EPOCH, null).stream()
                .filter(event -> List.of("authorization", "protection_rejection", "invalid_mcp_request",
                        "adapter_rejection").contains(event.getType()))
                .filter(event -> correlationId.equals(event.getData().get("correlationId")))
                .toList();
    }

    private List<AuditEvent> audits(String correlationId, String type) {
        return auditEventRepository.find(null, Instant.EPOCH, type).stream()
                .filter(event -> correlationId.equals(event.getData().get("correlationId")))
                .toList();
    }

    private GatewayToolExecutionContext assertAuthorizationContext(String clientId, String workspaceId,
                                                                    String correlationId) {
        ArgumentCaptor<GatewayToolExecutionContext> context = ArgumentCaptor.forClass(GatewayToolExecutionContext.class);
        verify(authorizationService).authorize(anyCollection(), context.capture());
        assertThat(context.getValue().principalId()).isEqualTo(clientId);
        assertThat(context.getValue().workspaceId()).isEqualTo(workspaceId);
        assertThat(context.getValue().correlationId()).isEqualTo(correlationId);
        assertThat(context.getValue().invocation()).isEqualTo(McpToolInvocation.fromJsonRpc("tools/call", ACTIVE_TOOL));
        return context.getValue();
    }

    private double authorizationCount(String outcome, String reason) {
        return counterCount("mcp.zap.authorization.decisions", "action", ACTIVE_TOOL,
                "outcome", outcome, "reason", reason);
    }

    private double counterCount(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long successfulExecutionCount() {
        Timer timer = meterRegistry.find("mcp.zap.tool.executions")
                .tags("tool", ACTIVE_TOOL, "outcome", "success").timer();
        return timer == null ? 0 : timer.count();
    }

    private void assertSuccessfulToolExecution(String correlationId, long previousExecutions) {
        assertThat(successfulExecutionCount()).isEqualTo(previousExecutions + 1);
        List<AuditEvent> executions = audits(correlationId, "tool_execution");
        assertThat(executions).hasSize(1);
        assertThat(executions.getFirst().getData()).containsEntry("tool", ACTIVE_TOOL).containsEntry("outcome", "success");
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
        return call(apiKey, sessionId, toolName, requestId, "tool-visibility-regression");
    }

    private EntityExchangeResult<String> call(String apiKey, String sessionId, String toolName, Object requestId,
                                             String correlationId) throws Exception {
        return post(apiKey, sessionId, Map.of(
                "jsonrpc", "2.0", "id", requestId, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments",
                        DISABLED_TOOL.equals(toolName) ? Map.of("scanId", "1") : Map.of())
        ), correlationId);
    }

    private EntityExchangeResult<String> post(String apiKey, String sessionId, Map<String, Object> payload)
            throws Exception {
        return post(apiKey, sessionId, payload, "tool-visibility-regression");
    }

    private EntityExchangeResult<String> post(String apiKey, String sessionId, Map<String, Object> payload,
                                             String correlationId) throws Exception {
        WebTestClient.RequestBodySpec request = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build()
                .post().uri("/mcp")
                .header("X-API-Key", apiKey)
                .header("X-Correlation-Id", correlationId)
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
