package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.authz.GuidedToolActions;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

/** Guided requests through real admission, launch ownership and storage, with a fake native engine. */
class GuidedQueuedScanIntegrationTest {
    private static final String TARGET = "https://example.invalid";

    @RegisterExtension
    final GuardedQueueTestSupport launches = new GuardedQueueTestSupport();

    private ScanJobStore store;
    private ScanJobQueueService queue;
    private GuidedScanWorkflowService guided;
    private final AtomicInteger nativeStarts = new AtomicInteger();
    private final AtomicInteger nativeStops = new AtomicInteger();
    private final AtomicBoolean lostReply = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    protected ScanJobStore newStore() throws Exception {
        return new InMemoryScanJobStore();
    }

    @BeforeEach
    void setUpGuidedQueue() throws Exception {
        store = newStore();
        Answer<Object> engine = invocation -> {
            Object[] arguments = invocation.getArguments();
            if (arguments.length > 0 && arguments[arguments.length - 1] instanceof NativeScanLaunch launch) {
                launch.beforeDispatch();
                assertThat(store.list()).anySatisfy(job -> {
                    assertThat(job.getStatus()).isEqualTo(ScanJobStatus.STARTING);
                    assertThat(job.getLaunchAttempt()).isNotNull();
                });
                int start = nativeStarts.incrementAndGet();
                if (lostReply.get()) throw new IllegalStateException("Fake engine accepted START but reply was lost");
                String id = "native-" + start;
                launch.accepted(id);
                return id;
            }
            String method = invocation.getMethod().getName();
            if (method.startsWith("start")) throw new AssertionError("Guided request bypassed guarded queue: " + method);
            if (method.startsWith("stop")) {
                nativeStops.incrementAndGet();
                terminated.set(true);
                return null;
            }
            if (method.endsWith("ProgressPercent")) return terminated.get() ? 100 : 0;
            if (method.equals("isAjaxSpiderRunning")) return !terminated.get();
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        };
        ActiveScanService active = mock(ActiveScanService.class, engine);
        SpiderScanService spider = mock(SpiderScanService.class, engine);
        AjaxSpiderService ajax = mock(AjaxSpiderService.class, engine);
        ClientSpiderService client = mock(ClientSpiderService.class, engine);
        ScanLimitProperties limits = new ScanLimitProperties();
        limits.setMaxConcurrentActiveScans(1);
        limits.setMaxConcurrentSpiderScans(1);
        queue = launches.guarded(new ScanJobQueueService(active, spider, ajax, client,
                mock(UrlValidationService.class), limits,
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1), false, store,
                new SingleNodeQueueLeadershipCoordinator()));
        GuidedToolActions actions = mock(GuidedToolActions.class);
        when(actions.canCall(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        guided = new GuidedScanWorkflowService(spider, ajax, client, active, queue,
                mock(GuidedAuthSessionService.class), new ZapEngineAdapter(), new GatewayRecordFactory(), actions);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void guidedHandleTracksAndStopsTheSameGuardedScan(Kind kind) {
        String response = start(kind, "one");
        assertThat(response).contains("Execution Mode: queue");
        ScanJob job = onlyJob();
        assertThat(job.getType()).isEqualTo(kind.type);
        assertThat(job.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
        assertThat(job.getZapScanId()).isEqualTo("native-1");
        assertThat(job.getLaunchAttempt()).isNotNull();
        String operationId = operationId(response);
        assertThat(status(kind, operationId)).contains("RUNNING");
        stop(kind, operationId);
        queue.processQueue();
        assertThat(store.load(job.getId()).orElseThrow().getStatus()).isEqualTo(ScanJobStatus.CANCELLED);
        assertThat(nativeStarts).hasValue(1);
        assertThat(nativeStops.get()).isPositive();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void lostReplyKeepsCapacityAndCannotLaunchAgainThroughGuidedStatusCancelOrAdmission(Kind kind) {
        lostReply.set(true);
        String response = start(kind, "uncertain");
        ScanJob job = onlyJob();
        assertThat(job.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
        assertThat(job.getZapScanId()).isNull();
        String attempt = job.getLaunchAttempt().id();
        String handle = operationId(response);
        assertThat(status(kind, handle)).contains("START_OUTCOME_UNKNOWN");
        stop(kind, handle);
        start(kind, "uncertain");
        String following = start(kind, "following");
        assertThat(following).contains("accepted.", "QUEUED");
        for (int pass = 0; pass < 3; pass++) queue.processQueue();
        ScanJob held = store.load(job.getId()).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
        assertThat(held.getLaunchAttempt().id()).isEqualTo(attempt);
        assertThat(held.isCancellationRequested()).isTrue();
        assertThat(store.list()).hasSize(2).anySatisfy(next -> {
            assertThat(next.getId()).isNotEqualTo(job.getId());
            assertThat(next.getStatus()).isEqualTo(ScanJobStatus.QUEUED);
        });
        assertThat(nativeStarts).hasValue(1);
        assertThat(nativeStops).hasValue(0);
    }

    @Test
    void idempotentGuidedAdmissionReusesJobAndRejectsChangedParameters() {
        String first = guided.startAttack(TARGET, "false", "policy-a", "same", null);
        String second = guided.startAttack(TARGET, "false", "policy-a", "same", null);
        ScanJob job = onlyJob();
        assertThat(job.getParameters()).containsEntry("recurse", "false").containsEntry("policy", "policy-a");
        assertThat(status(Kind.ATTACK, operationId(first))).contains("RUNNING");
        assertThat(status(Kind.ATTACK, operationId(second))).contains("RUNNING");
        assertThatThrownBy(() -> guided.startAttack(TARGET, "false", "policy-b", "same", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different queued scan request");
        assertThat(store.list()).hasSize(1);
        assertThat(nativeStarts).hasValue(1);
    }

    private String start(Kind kind, String key) {
        return kind == Kind.ATTACK ? guided.startAttack(TARGET, "true", null, key, null)
                : guided.startCrawl(TARGET, kind.name().toLowerCase(java.util.Locale.ROOT), key, null);
    }

    private String status(Kind kind, String handle) {
        return kind == Kind.ATTACK ? guided.getAttackStatus(handle) : guided.getCrawlStatus(handle);
    }

    private void stop(Kind kind, String handle) {
        if (kind == Kind.ATTACK) guided.stopAttack(handle);
        else guided.stopCrawl(handle);
    }

    private ScanJob onlyJob() {
        assertThat(store.list()).hasSize(1);
        return store.list().getFirst();
    }

    private String operationId(String response) {
        return response.lines().filter(line -> line.startsWith("Operation ID: "))
                .map(line -> line.substring("Operation ID: ".length())).findFirst().orElseThrow();
    }

    enum Kind {
        HTTP(ScanJobType.SPIDER_SCAN), AUTO(ScanJobType.SPIDER_SCAN), BROWSER(ScanJobType.AJAX_SPIDER),
        CLIENT(ScanJobType.CLIENT_SPIDER), ATTACK(ScanJobType.ACTIVE_SCAN);

        final ScanJobType type;
        Kind(ScanJobType type) { this.type = type; }
    }
}
