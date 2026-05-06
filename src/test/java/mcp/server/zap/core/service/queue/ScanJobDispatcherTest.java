package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanJobDispatcherTest {

    private ScanJobRuntimeExecutor runtimeExecutor;
    private ScanJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        runtimeExecutor = mock(ScanJobRuntimeExecutor.class);
        dispatcher = new ScanJobDispatcher(runtimeExecutor, Executors.newSingleThreadExecutor());
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    @Test
    void dispatchesPollAndStartTargetsThroughRuntimeExecutor() {
        Map<String, String> parameters = Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com");
        ScanJobClaimToken claimToken = claimToken();
        ScanJobPollTarget pollTarget = new ScanJobPollTarget(
                "job-running",
                ScanJobType.ACTIVE_SCAN,
                "active-1",
                claimToken
        );
        ScanJobStartTarget startTarget = new ScanJobStartTarget(
                "job-queued",
                ScanJobType.SPIDER_SCAN,
                parameters,
                claimToken
        );
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1")).thenReturn(42);
        when(runtimeExecutor.startScan(ScanJobType.SPIDER_SCAN, parameters)).thenReturn("spider-1");

        ScanJobDispatchResult result = dispatcher.dispatch(new ScanJobWorkPlan(
                List.of(pollTarget),
                List.of(startTarget)
        ));

        assertEquals(List.of(ScanJobPollResult.success(pollTarget, 42)), result.pollResults());
        assertEquals(
                List.of(ScanJobStartResult.success(startTarget, "spider-1")),
                result.startResults()
        );
        verify(runtimeExecutor).readProgress(ScanJobType.ACTIVE_SCAN, "active-1");
        verify(runtimeExecutor).startScan(ScanJobType.SPIDER_SCAN, parameters);
    }

    @Test
    void convertsRuntimeExceptionsIntoDispatchFailures() {
        Map<String, String> parameters = Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com");
        ScanJobClaimToken claimToken = claimToken();
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1"))
                .thenThrow(new RuntimeException("status boom"));
        when(runtimeExecutor.startScan(ScanJobType.SPIDER_SCAN, parameters))
                .thenThrow(new RuntimeException("startup boom"));

        ScanJobDispatchResult result = dispatcher.dispatch(new ScanJobWorkPlan(
                List.of(new ScanJobPollTarget("job-running", ScanJobType.ACTIVE_SCAN, "active-1", claimToken)),
                List.of(new ScanJobStartTarget("job-queued", ScanJobType.SPIDER_SCAN, parameters, claimToken))
        ));

        ScanJobPollResult pollResult = result.pollResults().getFirst();
        assertFalse(pollResult.success());
        assertEquals("job-running", pollResult.jobId());
        assertTrue(pollResult.error().contains("Runtime status check failed: status boom"));

        ScanJobStartResult startResult = result.startResults().getFirst();
        assertFalse(startResult.success());
        assertEquals("job-queued", startResult.jobId());
        assertTrue(startResult.error().contains("Startup failed: startup boom"));
    }

    @Test
    void timedOutPollDoesNotBlockStartDispatch() throws Exception {
        dispatcher.close();
        dispatcher = new ScanJobDispatcher(
                runtimeExecutor,
                Executors.newFixedThreadPool(2),
                Duration.ofMillis(100)
        );
        Map<String, String> parameters = Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com");
        ScanJobClaimToken claimToken = claimToken();
        CountDownLatch pollStarted = new CountDownLatch(1);
        CountDownLatch pollInterrupted = new CountDownLatch(1);
        when(runtimeExecutor.readProgress(ScanJobType.ACTIVE_SCAN, "active-1"))
                .thenAnswer(invocation -> {
                    pollStarted.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        pollInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                });
        when(runtimeExecutor.startScan(ScanJobType.SPIDER_SCAN, parameters)).thenReturn("spider-1");
        ScanJobPollTarget pollTarget = new ScanJobPollTarget(
                "job-running",
                ScanJobType.ACTIVE_SCAN,
                "active-1",
                claimToken
        );
        ScanJobStartTarget startTarget = new ScanJobStartTarget(
                "job-queued",
                ScanJobType.SPIDER_SCAN,
                parameters,
                claimToken
        );

        ScanJobDispatchResult result = dispatcher.dispatch(new ScanJobWorkPlan(
                List.of(pollTarget),
                List.of(startTarget)
        ));

        assertTrue(pollStarted.await(1, TimeUnit.SECONDS));
        assertTrue(pollInterrupted.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(ScanJobPollResult.failure(
                pollTarget,
                "Runtime status check failed: dispatch timed out"
        )), result.pollResults());
        assertEquals(List.of(ScanJobStartResult.success(startTarget, "spider-1")), result.startResults());
    }

    @Test
    void timedOutStartCleansUpLateScanIdWhenFutureCompletes() throws Exception {
        dispatcher.close();
        dispatcher = new ScanJobDispatcher(
                runtimeExecutor,
                Executors.newSingleThreadExecutor(),
                Duration.ofMillis(50)
        );
        Map<String, String> parameters = Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com");
        ScanJobClaimToken claimToken = claimToken();
        CountDownLatch stopCalled = new CountDownLatch(1);
        when(runtimeExecutor.startScan(ScanJobType.SPIDER_SCAN, parameters))
                .thenAnswer(invocation -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {
                        // Simulate a ZAP call that still produced a scan after cancellation was requested.
                    }
                    return "spider-late";
                });
        doAnswer(invocation -> {
            stopCalled.countDown();
            return null;
        }).when(runtimeExecutor).stopScan(ScanJobType.SPIDER_SCAN, "spider-late");
        ScanJobStartTarget startTarget = new ScanJobStartTarget(
                "job-late-start",
                ScanJobType.SPIDER_SCAN,
                parameters,
                claimToken
        );

        ScanJobDispatchResult result = dispatcher.dispatch(new ScanJobWorkPlan(
                List.of(),
                List.of(startTarget)
        ));

        ScanJobStartResult startResult = result.startResults().getFirst();
        assertFalse(startResult.success());
        assertEquals("Startup failed: dispatch timed out", startResult.error());
        assertTrue(stopCalled.await(1, TimeUnit.SECONDS));
        verify(runtimeExecutor).stopScan(ScanJobType.SPIDER_SCAN, "spider-late");
    }

    @Test
    void lateStartGuardCleansCompletedScanWhenTimeoutIsMarkedAfterCompletion() {
        ScanJobStartTarget target = startTarget("job-race", "https://example.com/race");
        AtomicReference<String> stoppedScanId = new AtomicReference<>();
        ScanJobDispatcher.LateStartGuard guard = new ScanJobDispatcher.LateStartGuard(
                target,
                (ignoredTarget, scanId) -> stoppedScanId.set(scanId)
        );

        assertTrue(guard.complete("spider-race"));
        guard.markTimedOut();

        assertEquals("spider-race", stoppedScanId.get());
    }

    @Test
    void lateStartGuardCleansLateCompletionOnlyOnce() {
        ScanJobStartTarget target = startTarget("job-late", "https://example.com/late");
        AtomicInteger cleanupCount = new AtomicInteger();
        ScanJobDispatcher.LateStartGuard guard = new ScanJobDispatcher.LateStartGuard(
                target,
                (ignoredTarget, scanId) -> cleanupCount.incrementAndGet()
        );

        guard.markTimedOut();
        assertFalse(guard.complete("spider-late"));
        guard.markTimedOut();

        assertEquals(1, cleanupCount.get());
    }

    @Test
    void cleanupStopsContinueAfterIndividualStopFailure() {
        doThrow(new RuntimeException("stop boom"))
                .when(runtimeExecutor).stopScan(ScanJobType.ACTIVE_SCAN, "active-1");

        dispatcher.executeStopRequests(List.of(
                new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-1"),
                new ScanJobStopRequest(ScanJobType.SPIDER_SCAN, "spider-1")
        ));

        verify(runtimeExecutor).stopScan(ScanJobType.ACTIVE_SCAN, "active-1");
        verify(runtimeExecutor).stopScan(ScanJobType.SPIDER_SCAN, "spider-1");
    }

    private ScanJobClaimToken claimToken() {
        return new ScanJobClaimToken(
                "node-a",
                "fence-1"
        );
    }

    private ScanJobStartTarget startTarget(String jobId, String targetUrl) {
        return new ScanJobStartTarget(
                jobId,
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, targetUrl),
                claimToken()
        );
    }
}
