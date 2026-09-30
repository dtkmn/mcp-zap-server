package mcp.server.zap.core.service.protection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import mcp.server.zap.core.service.GuidedExecutionModeResolver;
import mcp.server.zap.core.service.ScanJobQueueService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpAbuseProtectionServiceTest {
    private static final String CLIENT = "client-a";
    private static final String WORKSPACE = "workspace-a";
    private static final String TOOL = "zap_active_scan_start";
    private static final GatewayToolExecutionContext CONTEXT = GatewayToolExecutionContext.of(
            CLIENT, WORKSPACE, "rate-test-correlation", McpToolInvocation.fromJsonRpc("tools/call", TOOL), null
    );

    private final AbuseProtectionProperties properties = new AbuseProtectionProperties();
    private final ClientRateLimiter clientRateLimiter = mock(ClientRateLimiter.class);
    private final ClientWorkspaceResolver workspaceResolver = mock(ClientWorkspaceResolver.class);
    private final OperationRegistry operationRegistry = mock(OperationRegistry.class);
    private final ObjectProvider<ScanJobQueueService> queueProvider = mockProvider();
    private final GuidedExecutionModeResolver executionModeResolver = mock(GuidedExecutionModeResolver.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private McpAbuseProtectionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> meterRegistryProvider = mockProvider();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        properties.setRetryAfterSeconds(19L);
        properties.getWorkspaceQuota().setMaxDirectScans(1);
        properties.getBackpressure().setEnabled(false);
        service = new McpAbuseProtectionService(
                properties, clientRateLimiter, workspaceResolver, operationRegistry,
                queueProvider, executionModeResolver, new ToolScopeRegistry(), meterRegistryProvider
        );
    }

    @AfterEach
    void closeMeterRegistry() {
        meterRegistry.close();
    }

    @Test
    void rateRejectionUsesTheOriginalAttemptDelayAndContextWithoutASecondRateLookup() {
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(false, 37L));

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.reject(
                "rate_limited", "client_request_rate", TOOL, CLIENT, WORKSPACE, 37L
        ));
        verify(clientRateLimiter).attempt(CLIENT);
        verifyNoMoreInteractions(clientRateLimiter);
        verifyNoInteractions(operationRegistry, queueProvider, executionModeResolver);
        assertThat(counter("mcp.protection.rate_limited")).isEqualTo(1.0);
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void allowedRateAttemptStillAppliesWorkspaceQuotaWhetherRateLimitingIsEnabled(boolean rateLimitEnabled) {
        properties.getRateLimit().setEnabled(rateLimitEnabled);
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(true, 0L));
        when(operationRegistry.countDirectScans(WORKSPACE)).thenReturn(1);

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.reject(
                "workspace_quota_exceeded", "workspace_direct_scans", TOOL, CLIENT, WORKSPACE, 19L
        ));
        verify(clientRateLimiter).attempt(CLIENT);
        verifyNoMoreInteractions(clientRateLimiter);
        verify(operationRegistry).countDirectScans(WORKSPACE);
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isEqualTo(1.0);
    }

    @Test
    void allowedRateAttemptAndAvailableQuotaPermitTheRequest() {
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(true, 0L));
        when(operationRegistry.countDirectScans(WORKSPACE)).thenReturn(0);

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.allow(TOOL, CLIENT, WORKSPACE));
        verify(clientRateLimiter).attempt(CLIENT);
        verifyNoMoreInteractions(clientRateLimiter);
        verify(operationRegistry).countDirectScans(WORKSPACE);
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    @Test
    void disabledProtectionSkipsRateAndDomainEvaluation() {
        properties.setEnabled(false);

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.allow(TOOL, CLIENT, WORKSPACE));
        verifyNoInteractions(clientRateLimiter, operationRegistry, queueProvider, executionModeResolver,
                workspaceResolver);
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
        return mock(ObjectProvider.class);
    }
}
