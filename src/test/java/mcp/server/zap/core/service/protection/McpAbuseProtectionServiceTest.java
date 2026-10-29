package mcp.server.zap.core.service.protection;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private static final String TOOL = "zap_crawl_start";
    private static final GatewayToolExecutionContext CONTEXT = GatewayToolExecutionContext.of(
            CLIENT, WORKSPACE, "rate-test-correlation", McpToolInvocation.fromJsonRpc("tools/call", TOOL), null
    );

    private final AbuseProtectionProperties properties = new AbuseProtectionProperties();
    private final ClientRateLimiter clientRateLimiter = mock(ClientRateLimiter.class);
    private final ClientWorkspaceResolver workspaceResolver = mock(ClientWorkspaceResolver.class);
    private final ObjectProvider<ScanJobQueueService> queueProvider = mockProvider();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private McpAbuseProtectionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> meterRegistryProvider = mockProvider();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        properties.setRetryAfterSeconds(19L);
        properties.getWorkspaceQuota().setMaxQueuedOrRunningScanJobs(1);
        properties.getBackpressure().setEnabled(false);
        service = new McpAbuseProtectionService(
                properties, clientRateLimiter, workspaceResolver,
                queueProvider, new ToolScopeRegistry(), meterRegistryProvider
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
        verifyNoInteractions(queueProvider);
        assertThat(counter("mcp.protection.rate_limited")).isEqualTo(1.0);
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void allowedRateAttemptStillAppliesWorkspaceQuotaWhetherRateLimitingIsEnabled(boolean rateLimitEnabled) {
        properties.getRateLimit().setEnabled(rateLimitEnabled);
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(true, 0L));
        ScanJobQueueService queue = mock(ScanJobQueueService.class);
        when(queueProvider.getIfAvailable()).thenReturn(queue);
        when(queue.listJobsSnapshot()).thenReturn(List.of(new ScanJob("queued", ScanJobType.SPIDER_SCAN,
                Map.of(), Instant.now(), 2, CLIENT, null)));
        when(workspaceResolver.resolveWorkspaceId(CLIENT)).thenReturn(WORKSPACE);

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.reject(
                "workspace_quota_exceeded", "workspace_scan_jobs", TOOL, CLIENT, WORKSPACE, 19L
        ));
        verify(clientRateLimiter).attempt(CLIENT);
        verifyNoMoreInteractions(clientRateLimiter);
        verify(queueProvider).getIfAvailable();
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isEqualTo(1.0);
    }

    @Test
    void allowedRateAttemptAndAvailableQuotaPermitTheRequest() {
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(true, 0L));


        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.allow(TOOL, CLIENT, WORKSPACE));
        verify(clientRateLimiter).attempt(CLIENT);
        verifyNoMoreInteractions(clientRateLimiter);
        verify(queueProvider).getIfAvailable();
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    @Test
    void disabledProtectionSkipsRateAndDomainEvaluation() {
        properties.setEnabled(false);

        McpAbuseProtectionDecision decision = service.evaluate(CONTEXT);

        assertThat(decision).isEqualTo(McpAbuseProtectionDecision.allow(TOOL, CLIENT, WORKSPACE));
        verifyNoInteractions(clientRateLimiter, queueProvider,
                workspaceResolver);
        assertThat(counter("mcp.protection.rate_limited")).isZero();
        assertThat(counter("mcp.protection.workspace_quota_rejections")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zap_crawl_start", "zap_attack_start"})
    void guidedStartsUseQueueWorkspaceQuota(String tool) {
        ScanJobQueueService queue = useQueueAdmissionProtection();
        properties.getWorkspaceQuota().setMaxQueuedOrRunningScanJobs(1);
        ScanJob existing = new ScanJob("queued", ScanJobType.SPIDER_SCAN, Map.of(), Instant.now(), 2, CLIENT, null);
        when(queue.listJobsSnapshot()).thenReturn(List.of(existing));
        when(workspaceResolver.resolveWorkspaceId(CLIENT)).thenReturn(WORKSPACE);

        assertThat(service.evaluate(context(tool))).isEqualTo(McpAbuseProtectionDecision.reject(
                "workspace_quota_exceeded", "workspace_scan_jobs", tool, CLIENT, WORKSPACE, 19L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zap_crawl_start", "zap_attack_start"})
    void guidedStartsUseQueueBacklogBackpressure(String tool) {
        ScanJobQueueService queue = useQueueAdmissionProtection();
        properties.getWorkspaceQuota().setEnabled(false);
        properties.getBackpressure().setEnabled(true);
        properties.getBackpressure().setMaxTrackedScanJobs(1);
        when(queue.listJobsSnapshot()).thenReturn(List.of(
                new ScanJob("queued", ScanJobType.SPIDER_SCAN, Map.of(), Instant.now(), 2)));

        assertThat(service.evaluate(context(tool))).isEqualTo(McpAbuseProtectionDecision.reject(
                "overloaded", "scan_job_backlog", tool, CLIENT, WORKSPACE, 19L));
    }

    private ScanJobQueueService useQueueAdmissionProtection() {
        ScanJobQueueService queue = mock(ScanJobQueueService.class);
        when(queueProvider.getIfAvailable()).thenReturn(queue);
        when(clientRateLimiter.attempt(CLIENT)).thenReturn(new TokenBucketRateLimiter.Attempt(true, 0L));
        service = new McpAbuseProtectionService(properties, clientRateLimiter, workspaceResolver,
                queueProvider, new ToolScopeRegistry(), mockProvider());
        return queue;
    }

    private GatewayToolExecutionContext context(String tool) {
        return GatewayToolExecutionContext.of(CLIENT, WORKSPACE, "guided-quota-test",
                McpToolInvocation.fromJsonRpc("tools/call", tool), null);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider() {
        return mock(ObjectProvider.class);
    }
}
