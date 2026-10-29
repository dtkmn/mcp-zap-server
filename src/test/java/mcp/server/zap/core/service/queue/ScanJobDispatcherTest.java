package mcp.server.zap.core.service.queue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static mcp.server.zap.core.service.GuardedQueueTestSupport.launch;
import static mcp.server.zap.core.service.GuardedQueueTestSupport.startReplies;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScanJobDispatcherTest {
    @TempDir Path temporary;
    private final InMemoryScanJobStore store = new InMemoryScanJobStore();
    private ScanJobRuntimeExecutor runtimeExecutor;
    private ScanJobLaunchSafety safety;
    private ScanJobDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        runtimeExecutor = mock(ScanJobRuntimeExecutor.class);
        var properties = new ScanLaunchSafetyProperties();
        Path root = temporary.toRealPath();
        properties.setJournalDirectory(root.resolve("journal").toString());
        safety = new ScanJobLaunchSafety(store, properties, root.resolve("reports").toString(), root.resolve("af").toString());
        safety.configureWorker("worker", 30_000);
        safety.recover();
        dispatcher = configured(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        dispatcher.close();
        safety.close();
    }

    private ScanJobDispatcher configured(Duration timeout) {
        var value = new ScanJobDispatcher(runtimeExecutor, Executors.newFixedThreadPool(2), timeout);
        value.setLaunchSafety(safety);
        return value;
    }

    private ScanJobStartTarget target(ScanJobType type) {
        Instant now = Instant.now();
        ScanJob job = new ScanJob("job", type, Map.of("targetUrl", "https://example.com"), now, 3);
        job.claim("worker", now, now.plusSeconds(30));
        store.upsertAll(List.of(job));
        return new ScanJobStartTarget(job.getId(), type, job.getParameters(), ScanJobClaimToken.from(job));
    }

    @Test
    void unconfiguredDispatchAndStopFailBeforeAnyRuntimeCall() {
        try (var missing = new ScanJobDispatcher(runtimeExecutor, Executors.newSingleThreadExecutor())) {
            assertThrows(NullPointerException.class, () -> missing.dispatch(new ScanJobWorkPlan(List.of(), List.of())));
            assertThrows(NullPointerException.class, () -> missing.executeStopRequest(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "id")));
            verifyNoInteractions(runtimeExecutor);
        }
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void everyStartUsesItsGuardAndSavesReceiptBeforeReturning(ScanJobType type) {
        var target = target(type);
        when(runtimeExecutor.startScan(eq(target), any(NativeScanLaunch.class))).thenAnswer(startReplies("native-1"));
        var poll = new ScanJobPollTarget("other-running", ScanJobType.ACTIVE_SCAN, "active-1", new ScanJobClaimToken("worker", "fence"));
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1")).thenReturn(42);
        var result = dispatcher.dispatch(new ScanJobWorkPlan(List.of(poll), List.of(target)));
        assertEquals(List.of(ScanJobPollResult.success(poll, 42)), result.pollResults());
        assertEquals(List.of(ScanJobStartResult.success(target, "native-1")), result.startResults());
        ScanJob saved = store.load("job").orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, saved.getStatus());
        assertEquals("native-1", saved.getZapScanId());
        assertNotNull(saved.getLaunchAttempt());
        assertEquals(1, saved.getAttempts());
        verify(runtimeExecutor).startScan(eq(target), any(NativeScanLaunch.class));
    }

    @Test
    void distinguishesPreEffectBusyAndPreparationFailureFromPossibleExecution() {
        var target = target(ScanJobType.SPIDER_SCAN);
        when(runtimeExecutor.startScan(eq(target), any(NativeScanLaunch.class)))
                .thenThrow(new EngineBusyException("Engine busy", null))
                .thenThrow(new IllegalArgumentException("preparation failed"));
        var busy = dispatcher.dispatch(new ScanJobWorkPlan(List.of(), List.of(target)));
        assertEquals(List.of(ScanJobStartResult.busy(target, "Engine busy")), busy.startResults());
        assertEquals(ScanJobStatus.QUEUED, store.load("job").orElseThrow().getStatus());
        var failed = dispatcher.dispatch(new ScanJobWorkPlan(List.of(), List.of(target)));
        assertTrue(failed.startResults().getFirst().error().contains("preparation failed"));
        assertEquals(ScanJobStatus.QUEUED, store.load("job").orElseThrow().getStatus());
        assertNull(store.load("job").orElseThrow().getLaunchAttempt());
    }

    @Test
    void runtimePollErrorDoesNotObscureSuccessfulStart() {
        var target = target(ScanJobType.SPIDER_SCAN);
        when(runtimeExecutor.startScan(eq(target), any(NativeScanLaunch.class))).thenAnswer(startReplies("native-1"));
        var poll = new ScanJobPollTarget("other", ScanJobType.ACTIVE_SCAN, "active-1", new ScanJobClaimToken("worker", "fence"));
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1")).thenThrow(new IllegalStateException("status boom"));
        var result = dispatcher.dispatch(new ScanJobWorkPlan(List.of(poll), List.of(target)));
        assertTrue(result.pollResults().getFirst().error().contains("status boom"));
        assertTrue(result.startResults().getFirst().success());
    }

    @Test
    void timedOutPollDoesNotBlockStartReceipt() {
        dispatcher.close();
        dispatcher = configured(Duration.ofMillis(100));
        var target = target(ScanJobType.SPIDER_SCAN);
        when(runtimeExecutor.startScan(eq(target), any(NativeScanLaunch.class))).thenAnswer(startReplies("native-1"));
        var poll = new ScanJobPollTarget("other", ScanJobType.ACTIVE_SCAN, "active-1", new ScanJobClaimToken("worker", "fence"));
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1")).thenAnswer(invocation -> {
            Thread.sleep(10_000);
            return 0;
        });
        var result = dispatcher.dispatch(new ScanJobWorkPlan(List.of(poll), List.of(target)));
        assertTrue(result.pollResults().getFirst().error().contains("dispatch timed out"));
        assertTrue(result.startResults().getFirst().success());
        assertEquals(ScanJobStatus.RUNNING, store.load("job").orElseThrow().getStatus());
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void lateReceiptAdoptsSameHeldAttemptWithoutSendingCleanupStop(ScanJobType type) throws Exception {
        dispatcher.close();
        dispatcher = configured(Duration.ofMillis(100));
        var target = target(type);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        when(runtimeExecutor.startScan(eq(target), any(NativeScanLaunch.class))).thenAnswer(invocation -> {
            NativeScanLaunch launch = launch(invocation);
            launch.beforeDispatch();
            entered.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { }
            }
            try { launch.accepted("native-late"); return "native-late"; }
            finally { returned.countDown(); }
        });
        try {
            var result = dispatcher.dispatch(new ScanJobWorkPlan(List.of(), List.of(target)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals("Startup failed: dispatch timed out", result.startResults().getFirst().error());
            ScanJob held = store.load("job").orElseThrow();
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, held.getStatus());
            String attemptId = held.getLaunchAttempt().id();
            release.countDown();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
            ScanJob adopted = store.load("job").orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, adopted.getStatus());
            assertEquals(attemptId, adopted.getLaunchAttempt().id());
            assertEquals("native-late", adopted.getZapScanId());
            assertEquals(1, adopted.getAttempts());
            verify(runtimeExecutor, never()).stopScan(any(), any());
        } finally { release.countDown(); }
    }
}
