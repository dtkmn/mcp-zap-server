package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.queue.ScanJobLaunchSafety;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ScanJobReconciliationMcpToolsServiceTest {
    private static final String JOB_ID = "held-job";
    private static final String ATTEMPT_ID = "launch-attempt";
    private static final String OPERATOR = "recovery-operator";
    private static final String WORKSPACE = "shared-workspace";
    private static final long VERSION = 2;

    private ScanJobQueueService queue;
    private ScanJobLaunchSafety safety;
    private ScanLaunchSafetyProperties properties;
    private ToolAuthorizationProperties authorization;
    private MockEnvironment environment;
    private ScanJobReconciliationMcpToolsService service;

    @BeforeEach
    void setup() {
        queue = mock(ScanJobQueueService.class);
        safety = mock(ScanJobLaunchSafety.class);
        properties = new ScanLaunchSafetyProperties();
        properties.setOperatorClientIds(Set.of(OPERATOR));
        authorization = new ToolAuthorizationProperties();
        environment = new MockEnvironment()
                .withProperty("mcp.server.security.mode", "api-key")
                .withProperty("mcp.server.security.enabled", "true");
        service = new ScanJobReconciliationMcpToolsService(queue, safety, properties, authorization, environment);
        authenticate(OPERATOR, ScanJobReconciliationMcpToolsService.SCOPE);
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "zap:scan:read", "zap:scan:queue:write", "zap:scan:*", "zap:scan:queue:*"})
    void wildcardAndOrdinaryScanGrantsCannotInspectOrResolveLaunches(String scope) {
        authenticate(OPERATOR, scope);

        assertBothDeniedBeforeJobLookup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "disabled", "unknown"})
    void unauthenticatedSecurityModesCannotInspectOrResolveLaunches(String mode) {
        environment.setProperty("mcp.server.security.mode", mode);

        assertBothDeniedBeforeJobLookup();
    }

    @ParameterizedTest
    @EnumSource(value = ToolAuthorizationProperties.Mode.class, names = {"WARN", "OFF"})
    void nonEnforcingAuthorizationCannotInspectOrResolveLaunches(ToolAuthorizationProperties.Mode mode) {
        authorization.setMode(mode);

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void disabledSecurityRefusesRecoveryDespiteExactScopeAndApprovedIdentity() {
        environment.setProperty("mcp.server.security.enabled", "false");

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void exactScopeStillRequiresAnApprovedOperatorIdentity() {
        properties.setOperatorClientIds(Set.of());

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void paddedConfiguredIdentityDoesNotAuthorizeTheUnpaddedOperator() {
        properties.setOperatorClientIds(Set.of(" " + OPERATOR + " "));

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void authenticatedPrincipalAndRequestIdentityMustMatch() {
        RequestIdentityHolder.setClientId("routine-scanner");

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void missingRequestIdentityCannotUseAnAuthenticatedOperatorsScope() {
        RequestIdentityHolder.clear();

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void missingAuthenticationCannotUseAnApprovedRequestIdentity() {
        SecurityContextHolder.clearContext();

        assertBothDeniedBeforeJobLookup();
    }

    @Test
    void unauthenticatedTokenCannotUseItsExactGrantedAuthority() {
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);

        assertBothDeniedBeforeJobLookup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-key", "jwt"})
    void approvedAuthenticatedOperatorCanInspectWithoutDispatchOrSafetyMutation(String securityMode) {
        environment.setProperty("mcp.server.security.mode", securityMode);
        authorization.setAllowWildcard(false);
        when(queue.getReconciliationStatus(null)).thenReturn("visible held launch receipt");

        assertThat(service.status(null)).isEqualTo("visible held launch receipt");

        verify(queue).getReconciliationStatus(null);
        verifyNoMoreInteractions(queue);
        verifyNoInteractions(safety);
    }

    @Test
    void operatorStatusForwardsTheRequestedJobWithoutChangingIt() {
        when(queue.getReconciliationStatus(JOB_ID)).thenReturn("held job details");

        assertThat(service.status(JOB_ID)).isEqualTo("held job details");

        verify(queue).getReconciliationStatus(JOB_ID);
        verifyNoMoreInteractions(queue);
        verifyNoInteractions(safety);
    }

    @Test
    void reconciliationRequiresVisibilityBeforeForwardingTheExactReceiptAndVersion() {
        ScanJob original = heldJob(JOB_ID, "routine-scanner");
        ScanJob resolved = resolvedJob(JOB_ID, "routine-scanner");
        when(queue.requireReconciliationVisibleJob(JOB_ID)).thenReturn(original);
        when(queue.reconciliationWorkspaceId()).thenReturn(WORKSPACE);
        when(safety.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true))
                .thenReturn(resolved);

        assertThat(reconcile()).contains("Job ID: " + JOB_ID, "Status: CANCELLED",
                "Launch Attempt: " + ATTEMPT_ID, "Reconciliation Version: 3",
                "No new scan was launched and no scan success was asserted.");

        var order = inOrder(queue, safety);
        order.verify(queue).requireReconciliationVisibleJob(JOB_ID);
        order.verify(safety).reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true);
        order.verify(queue).reconciliationWorkspaceId();
        verifyNoMoreInteractions(queue, safety);
    }

    @Test
    void attachForwardsNativeIdAndConfirmationWithoutSubstitutingAnotherReceipt() {
        ScanJob attached = heldJob(JOB_ID, "routine-scanner");
        attached.restoreLaunchAttempt(attached.getLaunchAttempt().resolvedBy("current-controller"));
        attached.markRunning("native-42");
        when(safety.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "attach_scan", "native-42", true))
                .thenReturn(attached);

        assertThat(service.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "attach_scan", "native-42", true))
                .contains("Status: RUNNING", "Reconciliation Version: 3");

        verify(queue).requireReconciliationVisibleJob(JOB_ID);
        verify(safety).reconcile(JOB_ID, ATTEMPT_ID, VERSION, "attach_scan", "native-42", true);
    }

    @Test
    void staleReceiptFailureIsNotReportedAsSuccessfulReconciliation() {
        when(safety.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true))
                .thenThrow(new IllegalStateException("Launch attempt or reconciliation version changed; refresh status"));

        assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version changed");

        verify(queue).requireReconciliationVisibleJob(JOB_ID);
        verify(safety).reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true);
        verifyNoMoreInteractions(queue, safety);
    }

    @Test
    void separateOperatorIdentityCanRecoverSameWorkspaceJobUsingTheExistingBoundary() {
        try (QueueFixture fixture = queueFixture()) {
            fixture.store().upsertAll(List.of(heldJob(JOB_ID, "routine-scanner")));
            var actualVisibilityService = withQueue(fixture.queue());
            when(safety.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true))
                    .thenReturn(resolvedJob(JOB_ID, "routine-scanner"));

            assertThat(actualVisibilityService.status(null)).contains(JOB_ID, ATTEMPT_ID)
                    .doesNotContain("sensitive-target.test");
            assertThat(actualVisibilityService.reconcile(JOB_ID, ATTEMPT_ID, VERSION,
                    "confirm_stopped", null, true)).contains("Status: CANCELLED");

            fixture.assertNoNativeInteractions();
        }
    }

    @Test
    void otherWorkspaceIsHiddenFromBothRecoveryInspectionAndMutation() {
        try (QueueFixture fixture = queueFixture()) {
            fixture.store().upsertAll(List.of(heldJob(JOB_ID, "other-scanner")));
            var actualVisibilityService = withQueue(fixture.queue());

            assertThat(actualVisibilityService.status(null)).doesNotContain(JOB_ID, ATTEMPT_ID);
            assertThatThrownBy(() -> actualVisibilityService.status(JOB_ID))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("No scan job found for ID: " + JOB_ID);
            assertThatThrownBy(() -> actualVisibilityService.reconcile(JOB_ID, ATTEMPT_ID, VERSION,
                    "confirm_stopped", null, true)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No scan job found for ID: " + JOB_ID);

            verifyNoInteractions(safety);
            fixture.assertNoNativeInteractions();
        }
    }

    @Test
    void exactOperatorScopeDoesNotOverrideACustomSameWorkspaceDenial() {
        try (QueueFixture fixture = queueFixture()) {
            fixture.store().upsertAll(List.of(heldJob(JOB_ID, "routine-scanner")));
            fixture.queue().setScanJobAccessBoundary(job -> false);
            var actualVisibilityService = withQueue(fixture.queue());

            assertThat(actualVisibilityService.status(null)).doesNotContain(JOB_ID, ATTEMPT_ID);
            assertThatThrownBy(() -> actualVisibilityService.status(JOB_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> actualVisibilityService.reconcile(JOB_ID, ATTEMPT_ID, VERSION,
                    "confirm_stopped", null, true)).isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(safety);
            fixture.assertNoNativeInteractions();
        }
    }

    @Test
    void bothRecoveryToolsDeclareTheDedicatedScope() {
        ToolScopeRegistry registry = new ToolScopeRegistry();

        assertThat(registry.getRequiredScopes("zap_scan_job_reconcile"))
                .containsExactly(ScanJobReconciliationMcpToolsService.SCOPE);
        assertThat(registry.getRequiredScopes("zap_scan_job_reconciliation_status"))
                .containsExactly(ScanJobReconciliationMcpToolsService.SCOPE);
    }

    private void assertBothDeniedBeforeJobLookup() {
        assertThatThrownBy(this::reconcile).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.status(JOB_ID)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queue, safety);
    }

    private String reconcile() {
        return service.reconcile(JOB_ID, ATTEMPT_ID, VERSION, "confirm_stopped", null, true);
    }

    private void authenticate(String client, String scope) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                client, null, List.of(new SimpleGrantedAuthority("SCOPE_" + scope))));
        RequestIdentityHolder.set(client, WORKSPACE);
    }

    private ScanJobReconciliationMcpToolsService withQueue(ScanJobQueueService actualQueue) {
        return new ScanJobReconciliationMcpToolsService(actualQueue, safety, properties, authorization, environment);
    }

    private static ScanJob heldJob(String id, String requester) {
        ScanJob job = new ScanJob(id, ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "https://sensitive-target.test"), Instant.now(), 3, requester, null);
        job.beginLaunch(new ScanJob.LaunchAttempt(ATTEMPT_ID, "old-controller", Instant.now(), VERSION));
        job.markLaunchUnknown("Response was lost");
        return job;
    }

    private static ScanJob resolvedJob(String id, String requester) {
        ScanJob job = heldJob(id, requester);
        job.restoreLaunchAttempt(job.getLaunchAttempt().resolvedBy("current-controller"));
        job.markCancelled();
        return job;
    }

    private QueueFixture queueFixture() {
        ActiveScanService active = mock(ActiveScanService.class);
        SpiderScanService spider = mock(SpiderScanService.class);
        AjaxSpiderService ajax = mock(AjaxSpiderService.class);
        ClientSpiderService client = mock(ClientSpiderService.class);
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService actualQueue = new ScanJobQueueService(active, spider, ajax, client,
                mock(UrlValidationService.class), new ScanLimitProperties(),
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1), false,
                store, new SingleNodeQueueLeadershipCoordinator());
        ApiKeyProperties clients = new ApiKeyProperties();
        clients.setApiKeys(List.of(client(OPERATOR, WORKSPACE), client("routine-scanner", WORKSPACE),
                client("other-scanner", "other-workspace")));
        actualQueue.setClientWorkspaceResolver(new ClientWorkspaceResolver(clients));
        return new QueueFixture(actualQueue, store, active, spider, ajax, client);
    }

    private static ApiKeyProperties.ApiKeyClient client(String id, String workspace) {
        ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
        client.setClientId(id);
        client.setWorkspaceId(workspace);
        return client;
    }

    private record QueueFixture(ScanJobQueueService queue, InMemoryScanJobStore store,
                                ActiveScanService active, SpiderScanService spider,
                                AjaxSpiderService ajax, ClientSpiderService client) implements AutoCloseable {
        void assertNoNativeInteractions() {
            verifyNoInteractions(active, spider, ajax, client);
        }

        @Override
        public void close() {
            queue.shutdownExecutor();
        }
    }
}
