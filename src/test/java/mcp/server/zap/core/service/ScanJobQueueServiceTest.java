package mcp.server.zap.core.service;

import com.sun.net.httpserver.HttpServer;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import mcp.server.zap.core.gateway.ZapEngineAjaxSpiderExecution;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScanJobQueueServiceTest {

    private ActiveScanService activeScanService;
    private SpiderScanService spiderScanService;
    private AjaxSpiderService ajaxSpiderService;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;
    private ScanJobQueueService service;

    @BeforeEach
    void setup() {
        activeScanService = mock(ActiveScanService.class);
        spiderScanService = mock(SpiderScanService.class);
        ajaxSpiderService = mock(AjaxSpiderService.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(1);
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(1);

        service = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false
        );
    }

    @AfterEach
    void tearDown() {
        RequestIdentityHolder.clear();
    }

    private ScanJobQueueService newServiceWithPolicies(
            ScanJobQueueService.RetryPolicy activePolicy,
            ScanJobQueueService.RetryPolicy spiderPolicy
    ) {
        return new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                activePolicy,
                spiderPolicy,
                false
        );
    }

    @Test
    void queueActiveScanStartsImmediatelyWhenCapacityAvailable() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-101");

        String response = service.queueActiveScan("http://example.com", null, null, null);
        String jobId = extractJobId(response);

        ScanJob job = service.getJobForTesting(jobId);
        assertNotNull(job);
        assertEquals(ScanJobStatus.RUNNING, job.getStatus());
        assertEquals("A-101", job.getZapScanId());
        assertEquals(1, job.getAttempts());
        verify(urlValidationService).validateUrl("http://example.com");
        verify(activeScanService).startActiveScanJob("http://example.com", "true", null);
    }

    @Test
    void enforcesConcurrencyAndStartsQueuedJobWhenSlotFrees() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenReturn("A-1", "A-2");
        when(activeScanService.getActiveScanProgressPercent("A-1"))
                .thenReturn(0, 100);

        String firstResponse = service.queueActiveScan("http://example.com/1", "true", null, null);
        String secondResponse = service.queueActiveScan("http://example.com/2", "true", null, null);

        String firstJobId = extractJobId(firstResponse);
        String secondJobId = extractJobId(secondResponse);

        ScanJob firstJob = service.getJobForTesting(firstJobId);
        ScanJob secondJob = service.getJobForTesting(secondJobId);

        assertEquals(ScanJobStatus.RUNNING, firstJob.getStatus());
        assertEquals(ScanJobStatus.QUEUED, secondJob.getStatus());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());

        service.processQueueOnceForTesting();

        firstJob = service.getJobForTesting(firstJobId);
        secondJob = service.getJobForTesting(secondJobId);
        assertEquals(ScanJobStatus.SUCCEEDED, firstJob.getStatus());
        assertEquals(ScanJobStatus.QUEUED, secondJob.getStatus());

        service.processQueueOnceForTesting();

        secondJob = service.getJobForTesting(secondJobId);
        assertEquals(ScanJobStatus.RUNNING, secondJob.getStatus());
        assertEquals("A-2", secondJob.getZapScanId());
        verify(activeScanService, times(2)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void listScanJobsHidesJobsRejectedByAccessBoundary() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-101");

        service.queueActiveScan("http://example.com", null, null, null);
        service.setScanJobAccessBoundary(job -> false);

        String summary = service.listScanJobs(null);

        assertTrue(summary.contains("Total jobs: 0"));
        assertTrue(summary.contains("Queue depth: 0"));
        assertTrue(summary.contains("No jobs match current filter."));
    }

    @Test
    void getScanJobStatusRejectsJobsOutsideAccessBoundary() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-102");

        String response = service.queueActiveScan("http://example.com", null, null, null);
        String jobId = extractJobId(response);
        service.setScanJobAccessBoundary(job -> false);

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.getScanJobStatus(jobId)
        );

        assertEquals("No scan job found for ID: " + jobId, error.getMessage());
    }

    @Test
    void defaultWorkspaceBoundaryRejectsOtherWorkspaceQueueObjects() {
        ApiKeyProperties apiKeyProperties = new ApiKeyProperties();
        ApiKeyProperties.ApiKeyClient clientA = new ApiKeyProperties.ApiKeyClient();
        clientA.setClientId("client-a");
        clientA.setWorkspaceId("workspace-a");
        ApiKeyProperties.ApiKeyClient clientB = new ApiKeyProperties.ApiKeyClient();
        clientB.setClientId("client-b");
        clientB.setWorkspaceId("workspace-b");
        apiKeyProperties.setApiKeys(List.of(clientA, clientB));
        service.setClientWorkspaceResolver(new ClientWorkspaceResolver(apiKeyProperties));
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenReturn("A-client-a", "A-client-b");

        RequestIdentityHolder.set("client-a", "workspace-a");
        String clientAJobId = extractJobId(service.queueActiveScan("http://a.example.com", null, null, null));

        RequestIdentityHolder.set("client-b", "workspace-b");
        String clientBJobId = extractJobId(service.queueActiveScan("http://b.example.com", null, null, null));

        String clientBList = service.listScanJobs(null);
        assertTrue(clientBList.contains(clientBJobId));
        assertTrue(!clientBList.contains(clientAJobId));
        assertThrowsExactly(IllegalArgumentException.class, () -> service.getScanJobStatus(clientAJobId));

        RequestIdentityHolder.set("client-a", "workspace-a");
        assertThrowsExactly(IllegalArgumentException.class, () -> service.cancelScanJob(clientBJobId));
    }

    @Test
    void restoresDurableQueueSnapshotOnServiceRestart() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        ScanJobQueueService writerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-restart");

        String runningJobId = extractJobId(writerService.queueActiveScan("http://example.com/restore-1", "true", null, null));
        String queuedJobId = extractJobId(writerService.queueActiveScan("http://example.com/restore-2", "true", null, null));

        ScanJobQueueService restoredService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        ScanJob restoredRunning = restoredService.getJobForTesting(runningJobId);
        ScanJob restoredQueued = restoredService.getJobForTesting(queuedJobId);

        assertNotNull(restoredRunning);
        assertEquals(ScanJobStatus.RUNNING, restoredRunning.getStatus());
        assertEquals("A-restart", restoredRunning.getZapScanId());

        assertNotNull(restoredQueued);
        assertEquals(ScanJobStatus.QUEUED, restoredQueued.getStatus());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void restartRecoveryNormalizesDuplicateQueuedIds() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        ScanJob restoredJob = ScanJob.restore(
                "job-dup",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/dup", "recurse", "true", "policy", ""),
                Instant.now(),
                3,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0
        );
        restoredJob.assignQueuePosition(1);
        sharedJobStore.upsertAll(List.of(restoredJob));

        ScanJobQueueService restoredService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        String summary = restoredService.listScanJobs(null);
        assertTrue(summary.contains("Queue depth: 1"));
    }

    @Test
    void restartRecoveryRepairsRunningJobWithoutScanId() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();

        ScanJob invalidRunningJob = ScanJob.restore(
                "job-running-no-id",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/running", "recurse", "true", "policy", ""),
                Instant.now(),
                3,
                ScanJobStatus.RUNNING,
                1,
                null,
                null,
                Instant.now(),
                null,
                null,
                10,
                0
        );
        sharedJobStore.upsertAll(List.of(invalidRunningJob));

        ScanJobQueueService restoredService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        ScanJob repairedJob = restoredService.getJobForTesting("job-running-no-id");
        assertNotNull(repairedJob);
        assertEquals(ScanJobStatus.FAILED, repairedJob.getStatus());
        assertTrue(repairedJob.getLastError().contains("Missing ZAP scan ID"));
    }

    @Test
    void submittingReplicaCanClaimAndStartSharedQueuedJob() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(1);
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-follower");

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinator
        );

        String response = followerService.queueActiveScan("http://example.com/follower", "true", null, null);
        String jobId = extractJobId(response);

        ScanJob followerJob = followerService.getJobForTesting(jobId);
        assertNotNull(followerJob);
        assertEquals(ScanJobStatus.RUNNING, followerJob.getStatus());
        assertEquals("A-follower", followerJob.getZapScanId());
        assertEquals("node-b", followerJob.getClaimOwnerId());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());

        leaderService.processQueueOnceForTesting();

        ScanJob leaderJob = leaderService.getJobForTesting(jobId);
        assertNotNull(leaderJob);
        assertEquals(ScanJobStatus.RUNNING, leaderJob.getStatus());
        assertEquals("A-follower", leaderJob.getZapScanId());
        assertEquals("node-b", leaderJob.getClaimOwnerId());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void repeatedQueueAdmissionWithSameIdempotencyKeyReturnsExistingJob() {
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        String firstResponse = service.queueActiveScan("http://example.com/idempotent", "true", null, "req-123");
        String secondResponse = service.queueActiveScan("http://example.com/idempotent", "true", null, "req-123");

        String firstJobId = extractJobId(firstResponse);
        String secondJobId = extractJobId(secondResponse);
        ScanJob storedJob = service.getJobForTesting(firstJobId);

        assertEquals(firstJobId, secondJobId);
        assertNotNull(storedJob);
        assertEquals("req-123", storedJob.getIdempotencyKey());
        assertTrue(secondResponse.contains("existing job returned for idempotent retry"));
        assertTrue(service.listScanJobs(null).contains("Total jobs: 1"));
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestIsRejected() {
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        service.queueActiveScan("http://example.com/idempotent-a", "true", null, "req-456");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> service.queueActiveScan("http://example.com/idempotent-b", "true", null, "req-456")
        );

        assertTrue(exception.getMessage().contains("already been used"));
    }

    @Test
    void concurrentFollowerAdmissionsDeduplicateSharedIdempotencyKey() throws Exception {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-c");
        TestQueueLeadershipCoordinator followerCoordinatorA = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinatorB = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        ScanJobQueueService followerServiceA = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinatorA
        );
        ScanJobQueueService followerServiceB = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinatorB
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<String> firstAdmission = executor.submit(() -> {
                barrier.await();
                return followerServiceA.queueActiveScan("http://example.com/concurrent", "true", null, "req-789");
            });
            Future<String> secondAdmission = executor.submit(() -> {
                barrier.await();
                return followerServiceB.queueActiveScan("http://example.com/concurrent", "true", null, "req-789");
            });

            String firstJobId = extractJobId(firstAdmission.get());
            String secondJobId = extractJobId(secondAdmission.get());

            assertEquals(firstJobId, secondJobId);
            assertEquals(1, sharedJobStore.list().size());
            assertEquals("req-789", sharedJobStore.load(firstJobId).orElseThrow().getIdempotencyKey());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queuedAjaxSpiderUsesSharedJobLifecycle() {
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:1");
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true, false);

        String response = service.queueAjaxSpiderScan("http://example.com/spa", "ajax-req-1");
        String jobId = extractJobId(response);

        ScanJob runningJob = service.getJobForTesting(jobId);
        assertNotNull(runningJob);
        assertEquals(ScanJobStatus.RUNNING, runningJob.getStatus());
        assertEquals(ScanJobType.AJAX_SPIDER, runningJob.getType());
        assertEquals("ajax-spider:1", runningJob.getZapScanId());

        service.processQueueOnceForTesting();
        assertEquals(ScanJobStatus.RUNNING, service.getJobForTesting(jobId).getStatus());

        service.processQueueOnceForTesting();
        assertEquals(ScanJobStatus.SUCCEEDED, service.getJobForTesting(jobId).getStatus());
        verify(ajaxSpiderService).startAjaxSpiderJob(eq("http://example.com/spa"), anyString(), any(), any());
        verify(ajaxSpiderService, times(2)).isAjaxSpiderRunning();
    }

    @Test
    void onlyOneAjaxSpiderJobStartsAtATimeEvenWhenSpiderCapacityAllowsMore() {
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(5);
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:1", "ajax-spider:2");
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true, false);

        String firstJobId = extractJobId(service.queueAjaxSpiderScan("http://example.com/spa-1", "ajax-1"));
        String secondJobId = extractJobId(service.queueAjaxSpiderScan("http://example.com/spa-2", "ajax-2"));

        assertEquals(ScanJobStatus.RUNNING, service.getJobForTesting(firstJobId).getStatus());
        assertEquals(ScanJobStatus.QUEUED, service.getJobForTesting(secondJobId).getStatus());
        verify(ajaxSpiderService, times(1)).startAjaxSpiderJob(anyString(), anyString(), any(), any());

        service.processQueueOnceForTesting();
        assertEquals(ScanJobStatus.SUCCEEDED, service.getJobForTesting(firstJobId).getStatus());
        assertEquals(ScanJobStatus.QUEUED, service.getJobForTesting(secondJobId).getStatus());

        service.processQueueOnceForTesting();
        assertEquals(ScanJobStatus.RUNNING, service.getJobForTesting(secondJobId).getStatus());
        verify(ajaxSpiderService, times(2)).startAjaxSpiderJob(anyString(), anyString(), any(), any());
    }

    @Test
    void cancelledAjaxSpiderRemainsCancelledWhenCrawlerStops() {
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:1");
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true);
        String jobId = extractJobId(service.queueAjaxSpiderScan("http://example.com/spa", "ajax-cancel"));

        service.cancelScanJob(jobId);
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(false);
        service.processQueueOnceForTesting();

        assertEquals(ScanJobStatus.CANCELLED, service.getJobForTesting(jobId).getStatus());
        String status = service.getScanJobStatus(jobId);
        assertTrue(status.contains("Status: CANCELLED"));
        assertTrue(status.contains("Progress: unavailable"));
        assertFalse(status.contains("100%"));
        verify(ajaxSpiderService).stopAjaxSpiderJob();
    }

    @Test
    void failedAjaxStopRemainsPendingUntilRetrySucceedsWithoutConsumingStartupAttempts() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 0, "cancel-worker");
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:cancel");
        doThrow(new ZapApiException("Internal Error", null)).doNothing()
                .when(ajaxSpiderService).stopAjaxSpiderJob();
        try {
            String jobId = extractJobId(cancellingService.queueAjaxSpiderScan("http://example.com/spa", null));

            String response = cancellingService.cancelScanJob(jobId);

            ScanJob pending = store.load(jobId).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, pending.getStatus());
            assertEquals("ajax-spider:cancel", pending.getZapScanId());
            assertTrue(pending.isCancellationPending());
            assertEquals(1, pending.getCancelAttemptCount());
            assertEquals(1, pending.getAttempts());
            assertTrue(response.toLowerCase().contains("cancellation pending"));
            assertTrue(pending.getLastError().contains("Internal Error"));

            cancellingService.processQueueOnceForTesting();

            ScanJob cancelled = store.load(jobId).orElseThrow();
            assertEquals(ScanJobStatus.CANCELLED, cancelled.getStatus());
            assertFalse(cancelled.isCancellationPending());
            assertEquals(1, cancelled.getAttempts());
            verify(ajaxSpiderService, times(2)).stopAjaxSpiderJob();
            verify(ajaxSpiderService, times(1)).startAjaxSpiderJob(anyString(), anyString(), any(), any());
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void lateAjaxStartWithFailedCleanupRetainsOwnershipUntilRetryBeforeNextCrawl() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        EngineAjaxSpiderExecution engine = mock(EngineAjaxSpiderExecution.class);
        AjaxSpiderService ajax = new AjaxSpiderService(engine, urlValidationService);
        ajax.setScanJobStore(store);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseLateStart = new CountDownLatch(1);
        AtomicReference<Thread> lateStartThread = new AtomicReference<>();
        AtomicReference<String> currentCrawl = new AtomicReference<>();
        AtomicReference<String> lastStoppedCrawl = new AtomicReference<>();
        AtomicInteger startCalls = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();

        when(engine.startAjaxSpider(any())).thenAnswer(invocation -> {
            String scanId = startCalls.incrementAndGet() == 1 ? "ajax-spider:late" : "ajax-spider:newer";
            if (!currentCrawl.compareAndSet(null, scanId)) {
                throw new EngineBusyException("Previous AJAX crawl is still running", null);
            }
            if ("ajax-spider:late".equals(scanId)) {
                lateStartThread.set(Thread.currentThread());
                startEntered.countDown();
                try {
                    assertTrue(releaseLateStart.await(20, TimeUnit.SECONDS));
                } catch (InterruptedException ignored) {
                    // ZAP already accepted the start; interrupting the caller cannot undo it.
                    assertTrue(releaseLateStart.await(5, TimeUnit.SECONDS));
                }
            }
            return scanId;
        });
        doAnswer(invocation -> {
            if (stopCalls.incrementAndGet() == 1) {
                throw new ZapApiException("ZAP rejected cleanup while initializing", null);
            }
            lastStoppedCrawl.set(currentCrawl.getAndSet(null));
            return null;
        }).when(engine).stopAjaxSpider();
        when(engine.readAjaxSpiderStatus()).thenAnswer(invocation ->
                new EngineAjaxSpiderExecution.AjaxSpiderStatus(
                        currentCrawl.get() == null ? "stopped" : "running", "0", currentCrawl.get() != null));

        // One startup attempt prevents a fresh launch retry from hiding lost cleanup ownership.
        // Per-task virtual threads let the test join the complete late-result cleanup callback.
        ScanJobQueueService queue = new ScanJobQueueService(activeScanService, spiderScanService, ajax,
                urlValidationService, scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1), true, store);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> submission = caller.submit(() -> queue.queueAjaxSpiderScan("http://example.com/late", null));
            assertTrue(startEntered.await(3, TimeUnit.SECONDS));
            String jobId = extractJobId(submission.get(15, TimeUnit.SECONDS));
            assertEquals(1L, releaseLateStart.getCount(), "The queue must return before ZAP's delayed start response");

            releaseLateStart.countDown();
            assertTrue(lateStartThread.get().join(Duration.ofSeconds(5)), "Late cleanup must finish before inspecting state");
            assertEquals(1, stopCalls.get());
            assertEquals("ajax-spider:late", currentCrawl.get());

            ScanJob pending = store.load(jobId).orElseThrow();
            assertFalse(pending.getStatus().isTerminal(),
                    "A late AJAX crawl whose cleanup failed must remain tracked; actual status: " + pending.getStatus());
            assertEquals("ajax-spider:late", pending.getZapScanId());
            assertTrue(pending.isCancellationPending(), "Failed late cleanup must enter the cancellation retry path");
            assertThrows(EngineBusyException.class, () -> ajax.startAjaxSpider("http://example.com/direct"));
            assertEquals(1, startCalls.get(), "Pending cleanup must reserve AJAX ownership before contacting ZAP");

            // Submitting another job dispatches the next cleanup retry before its launch.
            String newerId = extractJobId(queue.queueAjaxSpiderScan("http://example.com/newer", null));
            assertEquals(ScanJobStatus.CANCELLED, store.load(jobId).orElseThrow().getStatus());
            assertEquals(ScanJobStatus.RUNNING, store.load(newerId).orElseThrow().getStatus());
            assertEquals(2, stopCalls.get());
            assertEquals(2, startCalls.get());
            assertEquals("ajax-spider:late", lastStoppedCrawl.get(), "Cleanup must stop the original crawl");
            assertEquals("ajax-spider:newer", currentCrawl.get());

            queue.processQueueOnceForTesting();
            assertEquals(2, stopCalls.get(), "Old cleanup must not issue another global stop after the newer crawl starts");
            assertEquals("ajax-spider:newer", currentCrawl.get());
            assertEquals(ScanJobStatus.RUNNING, store.load(newerId).orElseThrow().getStatus());
        } finally {
            releaseLateStart.countDown();
            queue.shutdownExecutor();
            caller.shutdownNow();
        }
    }

    @Test
    void delayedAjaxCleanupCannotStopNewerCrawlAfterOriginalCancellation() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        EngineAjaxSpiderExecution engine = mock(EngineAjaxSpiderExecution.class);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch releaseReturn = new CountDownLatch(1);
        AtomicReference<Thread> delayedThread = new AtomicReference<>();
        AtomicReference<String> currentCrawl = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        when(engine.startAjaxSpider(any())).thenAnswer(invocation -> {
            String scanId = starts.incrementAndGet() == 1 ? "ajax-spider:original" : "ajax-spider:newer";
            assertTrue(currentCrawl.compareAndSet(null, scanId));
            return scanId;
        });
        doAnswer(invocation -> {
            stops.incrementAndGet();
            currentCrawl.set(null);
            return null;
        }).when(engine).stopAjaxSpider();
        when(engine.readAjaxSpiderStatus()).thenAnswer(invocation ->
                new EngineAjaxSpiderExecution.AjaxSpiderStatus("running", "0", currentCrawl.get() != null));
        AjaxSpiderService ajax = new AjaxSpiderService(engine, urlValidationService) {
            @Override
            public String startAjaxSpiderJob(String url, String jobId, ScanJobClaimToken token, Consumer<String> onAccepted) {
                String scanId = super.startAjaxSpiderJob(url, jobId, token, onAccepted);
                if ("ajax-spider:original".equals(scanId)) {
                    delayedThread.set(Thread.currentThread());
                    accepted.countDown();
                    try {
                        assertTrue(releaseReturn.await(20, TimeUnit.SECONDS));
                    } catch (InterruptedException ignored) {
                        assertTrue(assertDoesNotThrow(() -> releaseReturn.await(5, TimeUnit.SECONDS)));
                    }
                }
                return scanId;
            }
        };
        ajax.setScanJobStore(store);
        ScanJobQueueService queue = new ScanJobQueueService(activeScanService, spiderScanService, ajax,
                urlValidationService, scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1), true, store);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> submission = caller.submit(() -> queue.queueAjaxSpiderScan("http://example.com/original", null));
            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            String originalId = extractJobId(submission.get(15, TimeUnit.SECONDS));
            assertEquals("ajax-spider:original", store.load(originalId).orElseThrow().getZapScanId());

            queue.cancelScanJob(originalId);
            String newerId = extractJobId(queue.queueAjaxSpiderScan("http://example.com/newer", null));
            assertEquals(ScanJobStatus.CANCELLED, store.load(originalId).orElseThrow().getStatus());
            assertEquals(ScanJobStatus.RUNNING, store.load(newerId).orElseThrow().getStatus());
            assertEquals(1, stops.get());

            releaseReturn.countDown();
            assertTrue(delayedThread.get().join(Duration.ofSeconds(5)));
            queue.processQueueOnceForTesting();

            assertEquals(1, stops.get(), "A stale cleanup callback must not send a global stop for a terminal job");
            assertEquals("ajax-spider:newer", currentCrawl.get());
            assertEquals(ScanJobStatus.RUNNING, store.load(newerId).orElseThrow().getStatus());
            assertEquals(1, store.load(originalId).orElseThrow().getAttempts());
        } finally {
            releaseReturn.countDown();
            queue.shutdownExecutor();
            caller.shutdownNow();
        }
    }

    @Test
    void ajaxStopReadTimeoutReturnsWithCancellationPendingAndKeepsNewCrawlsBlocked() throws Exception {
        CountDownLatch stopReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer zapServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        zapServer.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            stopReceived.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        zapServer.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        ScanJobQueueService cancellingService = null;
        try {
            var client = new TimeoutZapClientApi("127.0.0.1", zapServer.getAddress().getPort(), "", 200, 200);
            ajaxSpiderService = new AjaxSpiderService(new ZapEngineAjaxSpiderExecution(client), urlValidationService);
            InMemoryScanJobStore store = new InMemoryScanJobStore();
            ajaxSpiderService.setScanJobStore(store);
            ScanJob running = runningAjaxJob("stop-read-timeout", Instant.now());
            ScanJob next = new ScanJob("next-after-timeout", ScanJobType.AJAX_SPIDER,
                    Map.of("targetUrl", "http://example.com/next"), Instant.now(), 3);
            store.upsertAll(List.of(running, next));
            cancellingService = newAjaxCancellationService(store, 60_000, "timeout-worker");
            ScanJobQueueService queue = cancellingService;

            Future<String> cancellation = caller.submit(() -> queue.cancelScanJob(running.getId()));

            assertTrue(stopReceived.await(3, TimeUnit.SECONDS), "The stop request should reach ZAP");
            String response = cancellation.get(3, TimeUnit.SECONDS);
            assertEquals(1L, releaseResponse.getCount(), "Cancellation must return while ZAP still withholds its response");
            ScanJob pending = store.load(running.getId()).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, pending.getStatus());
            assertTrue(pending.isCancellationPending());
            assertEquals(1, pending.getCancelAttemptCount());
            assertEquals(1, pending.getAttempts());
            assertTrue(response.toLowerCase().contains("cancellation pending"));

            queue.processQueueOnceForTesting();
            assertEquals(ScanJobStatus.QUEUED, store.load(next.getId()).orElseThrow().getStatus());
            assertThrows(EngineBusyException.class,
                    () -> ajaxSpiderService.startAjaxSpider("http://example.com/direct"));
            assertEquals(1, requestCount.get(), "A timed-out stop must not release capacity or trigger immediate retries");
        } finally {
            releaseResponse.countDown();
            zapServer.stop(0);
            caller.shutdownNow();
            if (cancellingService != null) {
                cancellingService.shutdownExecutor();
            }
        }
    }

    @Test
    void ajaxCancellationUsesBackoffAndRepeatedRequestsPreserveOriginalDeadline() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 10_000, "cancel-worker");
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:backoff");
        doThrow(new ZapApiException("Stop request failed", null)).when(ajaxSpiderService).stopAjaxSpiderJob();
        try {
            String jobId = extractJobId(cancellingService.queueAjaxSpiderScan("http://example.com/spa", null));
            Instant beforeCancel = Instant.now();
            cancellingService.cancelScanJob(jobId);
            ScanJob pending = store.load(jobId).orElseThrow();
            Instant requestedAt = pending.getCancelRequestedAt();
            Instant deadline = pending.getCancelDeadlineAt();
            Instant retryAt = pending.getCancelNextAttemptAt();
            assertFalse(retryAt.isBefore(beforeCancel.plusSeconds(10)));

            cancellingService.processQueueOnceForTesting();
            cancellingService.cancelScanJob(jobId);

            ScanJob unchanged = store.load(jobId).orElseThrow();
            assertEquals(requestedAt, unchanged.getCancelRequestedAt());
            assertEquals(deadline, unchanged.getCancelDeadlineAt());
            assertEquals(retryAt, unchanged.getCancelNextAttemptAt());
            assertEquals(1, unchanged.getCancelAttemptCount());
            assertEquals(1, unchanged.getAttempts());
            verify(ajaxSpiderService, times(1)).stopAjaxSpiderJob();
            verify(ajaxSpiderService, never()).isAjaxSpiderRunning();
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void restoredWorkerResumesAjaxCancellationWithoutResettingItsDeadline() {
        Instant now = Instant.now();
        ScanJob pending = runningAjaxJob("cancel-restored", now.minusSeconds(90));
        pending.requestCancellation(now.minusSeconds(30), now.plusSeconds(60));
        pending.scheduleCancellationRetry(now.minusSeconds(1), "Stop request failed");
        pending.claim("former-worker", now.minusSeconds(30), now.minusSeconds(1));
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(pending));
        ScanJobQueueService restored = newAjaxCancellationService(store, 60_000, "restored-worker");
        try {
            ScanJob restoredPending = restored.getJobForTesting(pending.getId());
            assertTrue(restoredPending.isCancellationPending());
            assertEquals(now.minusSeconds(30), restoredPending.getCancelRequestedAt());
            assertEquals(now.plusSeconds(60), restoredPending.getCancelDeadlineAt());
            assertEquals(1, restoredPending.getCancelAttemptCount());

            restored.processQueueOnceForTesting();

            ScanJob cancelled = store.load(pending.getId()).orElseThrow();
            assertEquals(ScanJobStatus.CANCELLED, cancelled.getStatus());
            assertEquals(now.plusSeconds(60), cancelled.getCancelDeadlineAt());
            assertEquals(1, cancelled.getAttempts());
            verify(ajaxSpiderService).stopAjaxSpiderJob();
            verify(ajaxSpiderService, never()).startAjaxSpiderJob(anyString(), anyString(), any(), any());
        } finally {
            restored.shutdownExecutor();
        }
    }

    @Test
    void expiredAjaxCancellationRetainsCapacityWithoutLateStopsAndExplicitRequestOpensNewWindow() {
        Instant now = Instant.now();
        ScanJob pending = runningAjaxJob("cancel-expired", now.minusSeconds(120));
        pending.requestCancellation(now.minusSeconds(90), now.minusSeconds(1));
        ScanJob next = new ScanJob("next-after-expired", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com/next"), now, 3);
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(pending, next));
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true);
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 60_000, "cancel-worker");
        try {
            cancellingService.processQueueOnceForTesting();
            cancellingService.processQueueOnceForTesting();

            ScanJob unconfirmed = store.load(pending.getId()).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, unconfirmed.getStatus());
            assertTrue(unconfirmed.isCancellationRequested());
            assertFalse(unconfirmed.isCancellationPending());
            assertTrue(unconfirmed.getLastError().contains("Unable to confirm cancellation"));
            assertEquals(1, unconfirmed.getAttempts());
            assertEquals(ScanJobStatus.QUEUED, store.load(next.getId()).orElseThrow().getStatus());
            verify(ajaxSpiderService, never()).stopAjaxSpiderJob();
            verify(ajaxSpiderService, never()).startAjaxSpiderJob(anyString(), anyString(), any(), any());

            doThrow(new ZapApiException("Stop request failed again", null))
                    .when(ajaxSpiderService).stopAjaxSpiderJob();
            cancellingService.cancelScanJob(pending.getId());

            ScanJob retried = store.load(pending.getId()).orElseThrow();
            assertTrue(retried.isCancellationPending());
            assertTrue(retried.getCancelRequestedAt().isAfter(now.minusSeconds(90)));
            assertTrue(retried.getCancelDeadlineAt().isAfter(now));
            assertEquals(1, retried.getCancelAttemptCount());
            assertEquals(1, retried.getAttempts());
            assertEquals(ScanJobStatus.QUEUED, store.load(next.getId()).orElseThrow().getStatus());
            verify(ajaxSpiderService, times(1)).stopAjaxSpiderJob();
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void statusResultDispatchedBeforeCancellationCannotCompletePendingAjaxJob() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 60_000, "cancel-worker");
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:stale-status");
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenAnswer(invocation -> {
            pollEntered.countDown();
            assertTrue(releasePoll.await(5, TimeUnit.SECONDS));
            return false;
        });
        doThrow(new ZapApiException("Internal Error", null)).when(ajaxSpiderService).stopAjaxSpiderJob();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String jobId = extractJobId(cancellingService.queueAjaxSpiderScan("http://example.com/spa", null));
            Future<?> poll = executor.submit(cancellingService::processQueueOnceForTesting);
            assertTrue(pollEntered.await(5, TimeUnit.SECONDS));

            cancellingService.cancelScanJob(jobId);
            releasePoll.countDown();
            poll.get(5, TimeUnit.SECONDS);

            ScanJob pending = store.load(jobId).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, pending.getStatus());
            assertTrue(pending.isCancellationPending());
            assertEquals(1, pending.getAttempts());
            assertTrue(pending.getLastError().contains("Internal Error"));
            verify(ajaxSpiderService, times(1)).stopAjaxSpiderJob();
        } finally {
            releasePoll.countDown();
            executor.shutdownNow();
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void cancellationDuringClaimedAjaxStartupSurvivesDelayedStartResult() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 0, "cancel-worker");
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(releaseStart.await(5, TimeUnit.SECONDS));
            return "ajax-spider:delayed-start";
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> start = executor.submit(() ->
                    cancellingService.queueAjaxSpiderScan("http://example.com/spa", null));
            assertTrue(startEntered.await(5, TimeUnit.SECONDS));
            ScanJob starting = store.list().getFirst();
            String jobId = starting.getId();
            assertEquals(ScanJobStatus.QUEUED, starting.getStatus());
            assertTrue(starting.hasLiveClaim(Instant.now()));

            cancellingService.cancelScanJob(jobId);

            ScanJob pendingStart = store.load(jobId).orElseThrow();
            Instant requestedAt = pendingStart.getCancelRequestedAt();
            assertTrue(pendingStart.isCancellationPending());
            assertEquals(ScanJobStatus.QUEUED, pendingStart.getStatus());
            assertEquals(0, pendingStart.getAttempts());
            verify(ajaxSpiderService, never()).stopAjaxSpiderJob();

            releaseStart.countDown();
            start.get(5, TimeUnit.SECONDS);

            ScanJob running = store.load(jobId).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, running.getStatus());
            assertEquals("ajax-spider:delayed-start", running.getZapScanId());
            assertTrue(running.isCancellationPending());
            assertEquals(requestedAt, running.getCancelRequestedAt());
            assertEquals(1, running.getAttempts());

            cancellingService.processQueueOnceForTesting();

            assertEquals(ScanJobStatus.CANCELLED, store.load(jobId).orElseThrow().getStatus());
            verify(ajaxSpiderService, times(1)).stopAjaxSpiderJob();
            verify(ajaxSpiderService, times(1)).startAjaxSpiderJob(anyString(), anyString(), any(), any());
        } finally {
            releaseStart.countDown();
            executor.shutdownNow();
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void newerAjaxJobStartsOnlyAfterPendingStopIsAccepted() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 0, "cancel-worker");
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-spider:first", "ajax-spider:next");
        doThrow(new ZapApiException("First stop failed", null))
                .doThrow(new ZapApiException("Second stop failed", null)).doNothing()
                .when(ajaxSpiderService).stopAjaxSpiderJob();
        try {
            String firstId = extractJobId(cancellingService.queueAjaxSpiderScan("http://example.com/first", null));
            cancellingService.cancelScanJob(firstId);
            String nextId = extractJobId(cancellingService.queueAjaxSpiderScan("http://example.com/next", null));

            assertEquals(ScanJobStatus.RUNNING, store.load(firstId).orElseThrow().getStatus());
            assertTrue(store.load(firstId).orElseThrow().isCancellationPending());
            assertEquals(ScanJobStatus.QUEUED, store.load(nextId).orElseThrow().getStatus());
            verify(ajaxSpiderService, times(1)).startAjaxSpiderJob(anyString(), anyString(), any(), any());

            cancellingService.processQueueOnceForTesting();

            assertEquals(ScanJobStatus.CANCELLED, store.load(firstId).orElseThrow().getStatus());
            assertEquals(ScanJobStatus.RUNNING, store.load(nextId).orElseThrow().getStatus());
            var ordered = inOrder(ajaxSpiderService);
            ordered.verify(ajaxSpiderService).startAjaxSpiderJob(eq("http://example.com/first"), anyString(), any(), any());
            ordered.verify(ajaxSpiderService, times(3)).stopAjaxSpiderJob();
            ordered.verify(ajaxSpiderService).startAjaxSpiderJob(eq("http://example.com/next"), anyString(), any(), any());
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void ambiguousAjaxStartupCancellationDoesNotStopAnUnidentifiedCrawl() {
        Instant now = Instant.now();
        ScanJob pending = new ScanJob("ambiguous-start", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com/spa"), now, 3);
        pending.requestCancellation(now, now.plusSeconds(30));
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(pending));
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 0, "cancel-worker");
        try {
            cancellingService.processQueueOnceForTesting();
            assertEquals(ScanJobStatus.QUEUED, store.load(pending.getId()).orElseThrow().getStatus());
            assertTrue(store.load(pending.getId()).orElseThrow().isCancellationPending());
            verify(ajaxSpiderService, never()).stopAjaxSpiderJob();
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    @Test
    void conflictingAjaxOwnersPreventGlobalCancellation() {
        Instant now = Instant.now();
        ScanJob pending = runningAjaxJob("pending-owner", now);
        pending.requestCancellation(now, now.plusSeconds(30));
        ScanJob other = runningAjaxJob("conflicting-owner", now);
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(pending, other));
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true);
        ScanJobQueueService cancellingService = newAjaxCancellationService(store, 0, "cancel-worker");
        try {
            cancellingService.processQueueOnceForTesting();
            assertTrue(store.load(pending.getId()).orElseThrow().isCancellationPending());
            assertEquals(ScanJobStatus.RUNNING, store.load(other.getId()).orElseThrow().getStatus());
            verify(ajaxSpiderService, never()).stopAjaxSpiderJob();
        } finally {
            cancellingService.shutdownExecutor();
        }
    }

    private ScanJobQueueService newAjaxCancellationService(InMemoryScanJobStore store, long retryDelayMs, String workerId) {
        return new ScanJobQueueService(activeScanService, spiderScanService, ajaxSpiderService,
                urlValidationService, scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(3, retryDelayMs, retryDelayMs, 1),
                false, store, new TestQueueLeadershipCoordinator(workerId, new SharedLeadershipState(workerId)));
    }

    private ScanJob runningAjaxJob(String jobId, Instant createdAt) {
        ScanJob job = new ScanJob(jobId, ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com/spa"), createdAt, 3);
        job.incrementAttempts();
        job.markRunning("ajax-spider:" + jobId);
        return job;
    }

    @Test
    void busyEngineWaitsWithoutConsumingAttemptsAndThenStarts() {
        ScanJobQueueService waitingService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1));
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new EngineBusyException("Engine is busy", null))
                .thenReturn("active-after-wait");
        try {
            String response = waitingService.queueActiveScan("http://example.com", "true", null, null);
            String jobId = extractJobId(response);
            ScanJob waiting = waitingService.getJobForTesting(jobId);
            assertTrue(response.contains("QUEUED (waiting for engine)"));
            assertEquals(0, waiting.getAttempts());
            assertEquals(1, waiting.getBusyWaitCount());
            assertNull(waiting.getClaimOwnerId());

            waitingService.processQueueOnceForTesting();

            ScanJob running = waitingService.getJobForTesting(jobId);
            assertEquals(ScanJobStatus.RUNNING, running.getStatus());
            assertEquals(1, running.getAttempts());
            assertNull(running.getBusyWaitStartedAt());
        } finally {
            waitingService.shutdownExecutor();
        }
    }

    @Test
    void waitingJobCanBeCancelledBeforeEngineStarts() {
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any()))
                .thenThrow(new EngineBusyException("Engine is busy", null));
        String jobId = extractJobId(service.queueAjaxSpiderScan("http://example.com", null));

        service.cancelScanJob(jobId);
        service.processQueueOnceForTesting();

        assertEquals(ScanJobStatus.CANCELLED, service.getJobForTesting(jobId).getStatus());
        assertEquals(0, service.getJobForTesting(jobId).getAttempts());
        verify(ajaxSpiderService, times(1)).startAjaxSpiderJob(anyString(), anyString(), any(), any());
        verify(ajaxSpiderService, never()).stopAjaxSpiderJob();
    }

    @Test
    void restoredBusyWaitExpiresDespiteFullCapacityAndLaterBackoffAndCanBeRetried() {
        Instant now = Instant.now();
        ScanJob waiting = new ScanJob("busy-expired", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com"), now.minusSeconds(300), 2);
        waiting.markWaitingForEngine(now.minusSeconds(181), now.plusSeconds(600), "Engine is busy");
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(waiting));
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(0);
        ScanJobQueueService restored = new ScanJobQueueService(activeScanService, spiderScanService,
                ajaxSpiderService, urlValidationService, scanLimitProperties, 2, false, store);
        try {
            restored.processQueueOnceForTesting();
            ScanJob expired = store.load("busy-expired").orElseThrow();
            assertEquals(ScanJobStatus.FAILED, expired.getStatus());
            assertEquals(0, expired.getAttempts());
            assertTrue(expired.getLastError().contains("Engine busy wait timed out after 180000 ms"));
            verify(ajaxSpiderService, never()).startAjaxSpiderJob(anyString(), anyString(), any(), any());

            when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(1);
            when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any())).thenReturn("ajax-after-manual-retry");
            restored.retryScanJob("busy-expired");

            assertEquals(ScanJobStatus.RUNNING, store.load("busy-expired").orElseThrow().getStatus());
            assertEquals(1, store.load("busy-expired").orElseThrow().getAttempts());
            assertNull(store.load("busy-expired").orElseThrow().getBusyWaitStartedAt());
        } finally {
            restored.shutdownExecutor();
        }
    }

    @Test
    void busyWaitDeadlineDoesNotExpireAnotherWorkersLiveStartClaim() {
        Instant now = Instant.now();
        ScanJob waiting = new ScanJob("busy-in-flight", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com"), now.minusSeconds(300), 2);
        waiting.markWaitingForEngine(now.minusSeconds(181), now.minusSeconds(1), "Engine is busy");
        waiting.claim("another-worker", now, now.plusSeconds(60));
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(waiting));
        ScanJobQueueService restored = new ScanJobQueueService(activeScanService, spiderScanService,
                ajaxSpiderService, urlValidationService, scanLimitProperties, 2, false, store);
        try {
            restored.processQueueOnceForTesting();
            assertEquals(ScanJobStatus.QUEUED, store.load("busy-in-flight").orElseThrow().getStatus());
            assertEquals("another-worker", store.load("busy-in-flight").orElseThrow().getClaimOwnerId());
            verify(ajaxSpiderService, never()).startAjaxSpiderJob(anyString(), anyString(), any(), any());
        } finally {
            restored.shutdownExecutor();
        }
    }

    @Test
    void configuredZeroEngineBusyWaitFailsImmediatelyWithoutConsumingAttempts() {
        when(ajaxSpiderService.startAjaxSpiderJob(anyString(), anyString(), any(), any()))
                .thenThrow(new EngineBusyException("Engine is busy", null));
        new ApplicationContextRunner()
                .withBean(ActiveScanService.class, () -> activeScanService)
                .withBean(SpiderScanService.class, () -> spiderScanService)
                .withBean(AjaxSpiderService.class, () -> ajaxSpiderService)
                .withBean(UrlValidationService.class, () -> urlValidationService)
                .withBean(ScanLimitProperties.class, () -> scanLimitProperties)
                .withUserConfiguration(ScanJobQueueService.class)
                .withPropertyValues("zap.scan.queue.engine-busy-max-wait-ms=0")
                .run(context -> {
                    ScanJobQueueService configured = context.getBean(ScanJobQueueService.class);
                    String jobId = extractJobId(configured.queueAjaxSpiderScan("http://example.com", null));
                    ScanJob job = configured.getJobForTesting(jobId);
                    assertEquals(ScanJobStatus.FAILED, job.getStatus());
                    assertEquals(0, job.getAttempts());
                    assertTrue(job.getLastError().contains("timed out after 0 ms"));
                });
    }

    @Test
    void followerReplicaCanCancelQueuedJob() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinator
        );

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/cancel", "true", null, null));
        String cancelMessage = followerService.cancelScanJob(jobId);
        ScanJob cancelledJob = followerService.getJobForTesting(jobId);

        assertTrue(cancelMessage.contains("cancelled"));
        assertNotNull(cancelledJob);
        assertEquals(ScanJobStatus.CANCELLED, cancelledJob.getStatus());
    }

    @Test
    void followerReplicaCanRetryCancelledJob() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0, 0, 1, 1);
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-retried");

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinator
        );

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/retry", "true", null, null));
        leaderService.cancelScanJob(jobId);

        String retryMessage = followerService.retryScanJob(jobId);
        assertTrue(retryMessage.contains("Retry queued"));

        leadershipState.setLeaderId("node-a");
        leaderService.processQueueOnceForTesting();

        ScanJob retriedJob = leaderService.getJobForTesting(jobId);
        assertNotNull(retriedJob);
        assertEquals(ScanJobStatus.RUNNING, retriedJob.getStatus());
        assertEquals("A-retried", retriedJob.getZapScanId());
    }

    @Test
    void followerReplicaCanRequeueDeadLetterJob() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("first failure", new RuntimeException("first failure")))
                .thenReturn("A-replayed");

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0),
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0),
                false,
                sharedJobStore,
                followerCoordinator
        );

        String sourceJobId = extractJobId(leaderService.queueActiveScan("http://example.com/dead-letter-follower", "true", null, null));
        ScanJob sourceJob = leaderService.getJobForTesting(sourceJobId);
        assertEquals(ScanJobStatus.FAILED, sourceJob.getStatus());

        String replayResponse = followerService.requeueDeadLetterJob(sourceJobId);
        String replayJobId = extractValueByPrefix(replayResponse, "New Job ID: ");

        leaderService.processQueueOnceForTesting();

        ScanJob replayJob = leaderService.getJobForTesting(replayJobId);
        assertNotNull(replayJob);
        assertEquals(ScanJobStatus.RUNNING, replayJob.getStatus());
        assertEquals("A-replayed", replayJob.getZapScanId());
    }

    @Test
    void scanJobStoreTracksEnqueuedAndRunningJobs() {
        InMemoryScanJobStore scanJobStore = new InMemoryScanJobStore();
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-store");

        ScanJobQueueService storeBackedService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                scanJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        String jobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store", "true", null, null));

        ScanJob storedJob = scanJobStore.load(jobId).orElse(null);
        assertNotNull(storedJob);
        assertEquals(ScanJobStatus.RUNNING, storedJob.getStatus());
        assertEquals("A-store", storedJob.getZapScanId());
        assertEquals(0, storedJob.getQueuePosition());
    }

    @Test
    void queuedJobsPersistQueuePositionInScanJobStore() {
        InMemoryScanJobStore scanJobStore = new InMemoryScanJobStore();
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        ScanJobQueueService storeBackedService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                scanJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        String firstJobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store-q1", "true", null, null));
        String secondJobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store-q2", "true", null, null));

        ScanJob firstStoredJob = scanJobStore.load(firstJobId).orElse(null);
        ScanJob secondStoredJob = scanJobStore.load(secondJobId).orElse(null);

        assertNotNull(firstStoredJob);
        assertNotNull(secondStoredJob);
        assertEquals(1, firstStoredJob.getQueuePosition());
        assertEquals(2, secondStoredJob.getQueuePosition());

        String detail = storeBackedService.getScanJobStatus(secondJobId);
        String queuedSummary = storeBackedService.listScanJobs("QUEUED");
        assertTrue(detail.contains("Queue Position: 2"));
        assertTrue(queuedSummary.contains("queuePosition=1"));
        assertTrue(queuedSummary.contains("queuePosition=2"));
    }

    @Test
    void statusReadCanUseScanJobStoreWithoutQueueSnapshotRestore() {
        InMemoryScanJobStore scanJobStore = new InMemoryScanJobStore();
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(0);

        ScanJob storedJob = ScanJob.restore(
                "job-store-only",
                ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", "http://example.com/store-only"),
                Instant.now(),
                2,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                3
        );
        scanJobStore.upsertAll(List.of(storedJob));

        ScanJobQueueService readService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                scanJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );

        String detail = readService.getScanJobStatus("job-store-only");
        assertTrue(detail.contains("Job ID: job-store-only"));
        assertTrue(detail.contains("Queue Position: 1"));
    }

    @Test
    void failoverPromotesFollowerAndResumesQueuedDispatchWithoutDuplicateStart() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        AtomicInteger activeCapacity = new AtomicInteger(0);
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenAnswer(ignored -> activeCapacity.get());

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinator
        );

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-failover");

        String queuedJobId = extractJobId(leaderService.queueActiveScan("http://example.com/failover", "true", null, null));
        verify(activeScanService, never()).startActiveScanJob(anyString(), anyString(), any());

        leadershipState.setLeaderId("node-b");
        activeCapacity.set(1);
        followerService.processQueueOnceForTesting();

        ScanJob resumedJob = followerService.getJobForTesting(queuedJobId);
        assertNotNull(resumedJob);
        assertEquals(ScanJobStatus.RUNNING, resumedJob.getStatus());
        assertEquals("A-failover", resumedJob.getZapScanId());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void expiredRunningClaimCanBeRecoveredWithoutDuplicateStart() {
        InMemoryScanJobStore sharedJobStore = new InMemoryScanJobStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-recover");
        when(activeScanService.getActiveScanProgressPercent("A-recover")).thenReturn(100);

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                sharedJobStore,
                followerCoordinator
        );

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/recover-running", "true", null, null));
        ScanJob runningJob = sharedJobStore.load(jobId).orElseThrow();
        runningJob.claim("node-a", Instant.now().minusSeconds(30), Instant.now().minusSeconds(1));
        sharedJobStore.upsertAll(List.of(runningJob));

        followerService.processQueueOnceForTesting();

        ScanJob recoveredJob = sharedJobStore.load(jobId).orElseThrow();
        assertEquals(ScanJobStatus.SUCCEEDED, recoveredJob.getStatus());
        verify(activeScanService, times(1)).startActiveScanJob("http://example.com/recover-running", "true", null);
        verify(activeScanService).getActiveScanProgressPercent("A-recover");
    }

    @Test
    void cancelQueuedJobMarksCancelled() {
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        String response = service.queueActiveScan("http://example.com", "true", null, null);
        String jobId = extractJobId(response);

        String cancelMessage = service.cancelScanJob(jobId);
        ScanJob job = service.getJobForTesting(jobId);

        assertTrue(cancelMessage.contains("cancelled"));
        assertEquals(ScanJobStatus.CANCELLED, job.getStatus());
        verify(activeScanService, never()).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void retryCancelledJobStartsAgainWhenRetryBudgetAvailable() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenReturn("A-1")
                .thenReturn("A-retry");

        String response = service.queueActiveScan("http://example.com", "true", null, null);
        String jobId = extractJobId(response);

        service.cancelScanJob(jobId);
        ScanJob cancelledJob = service.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.CANCELLED, cancelledJob.getStatus());
        assertEquals(1, cancelledJob.getAttempts());

        String retryMessage = service.retryScanJob(jobId);

        ScanJob retriedJob = service.getJobForTesting(jobId);
        assertTrue(retryMessage.contains("Retry queued"));
        assertEquals(ScanJobStatus.RUNNING, retriedJob.getStatus());
        assertEquals(2, retriedJob.getAttempts());
        assertEquals("A-retry", retriedJob.getZapScanId());
        verify(activeScanService).stopActiveScanJob("A-1");
        verify(activeScanService, times(2)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void cancelRunningJobStopsZapScan() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-stop");

        String response = service.queueActiveScan("http://example.com", "true", null, null);
        String jobId = extractJobId(response);

        String cancelMessage = service.cancelScanJob(jobId);
        ScanJob job = service.getJobForTesting(jobId);

        assertTrue(cancelMessage.contains("RUNNING"));
        assertEquals(ScanJobStatus.CANCELLED, job.getStatus());
        verify(activeScanService).stopActiveScanJob("A-stop");
    }

    @Test
    void startupBackoffDefersRetryUntilDueTime() {
        ScanJobQueueService delayedService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(3, 60_000, 60_000, 2.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("boom", new RuntimeException("boom")));

        String response = delayedService.queueActiveScan("http://example.com", "true", null, null);
        String jobId = extractJobId(response);

        ScanJob job = delayedService.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.QUEUED, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertNotNull(job.getNextAttemptAt());

        delayedService.processQueueOnceForTesting();

        ScanJob unchangedJob = delayedService.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals(1, unchangedJob.getAttempts());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void retryBudgetIsAppliedPerScanType() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("active boom", new RuntimeException("active boom")));
        when(spiderScanService.startSpiderScanJob(anyString()))
                .thenThrow(new ZapApiException("spider boom", new RuntimeException("spider boom")));

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null, null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider", null));

        policyService.processQueueOnceForTesting();
        policyService.processQueueOnceForTesting();

        ScanJob activeJob = policyService.getJobForTesting(activeJobId);
        ScanJob spiderJob = policyService.getJobForTesting(spiderJobId);

        assertEquals(ScanJobStatus.FAILED, activeJob.getStatus());
        assertEquals(3, activeJob.getAttempts());
        assertEquals(ScanJobStatus.FAILED, spiderJob.getStatus());
        assertEquals(2, spiderJob.getAttempts());
        verify(activeScanService, times(3)).startActiveScanJob(anyString(), anyString(), any());
        verify(spiderScanService, times(2)).startSpiderScanJob(anyString());
    }

    @Test
    void queuedJobsInheritTypeSpecificMaxAttemptDefaults() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(4, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-1");
        when(spiderScanService.startSpiderScanJob(anyString())).thenReturn("S-1");

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null, null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider", null));

        ScanJob activeJob = policyService.getJobForTesting(activeJobId);
        ScanJob spiderJob = policyService.getJobForTesting(spiderJobId);

        assertEquals(4, activeJob.getMaxAttempts());
        assertEquals(2, spiderJob.getMaxAttempts());
    }

    @Test
    void deadLetterListShowsRetryExhaustedJobs() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("active boom", new RuntimeException("active boom")));

        String failedJobId = extractJobId(policyService.queueActiveScan("http://example.com/dead-letter", "true", null, null));

        policyService.processQueueOnceForTesting();

        ScanJob failedJob = policyService.getJobForTesting(failedJobId);
        assertEquals(ScanJobStatus.FAILED, failedJob.getStatus());
        assertEquals(2, failedJob.getAttempts());

        String deadLetterOutput = policyService.listDeadLetterJobs();
        assertTrue(deadLetterOutput.contains("Dead-letter jobs: 1"));
        assertTrue(deadLetterOutput.contains(failedJobId));
    }

    @Test
    void deadLetterRequeueCreatesNewJobWithFreshBudget() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("first failure", new RuntimeException("first failure")))
                .thenReturn("A-replayed");

        String sourceJobId = extractJobId(policyService.queueActiveScan("http://example.com/requeue", "true", null, null));
        ScanJob sourceJob = policyService.getJobForTesting(sourceJobId);
        assertEquals(ScanJobStatus.FAILED, sourceJob.getStatus());

        String replayResponse = policyService.requeueDeadLetterJob(sourceJobId);
        String replayJobId = extractValueByPrefix(replayResponse, "New Job ID: ");
        ScanJob replayJob = policyService.getJobForTesting(replayJobId);

        assertNotNull(replayJob);
        assertEquals(ScanJobStatus.RUNNING, replayJob.getStatus());
        assertEquals("A-replayed", replayJob.getZapScanId());
        assertEquals(1, replayJob.getAttempts());
        assertTrue(replayResponse.contains("Source Job ID: " + sourceJobId));
        verify(activeScanService, times(2)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void successfulStartIsStoppedWhenFailFastPersistenceThrows() {
        FailingUpdateScanJobStore failingStore = new FailingUpdateScanJobStore();
        ScanJobQueueService failFastService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0),
                false,
                failingStore,
                new SingleNodeQueueLeadershipCoordinator()
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-orphan");

        assertThrows(IllegalStateException.class, () ->
                failFastService.queueActiveScan("http://example.com/orphan", "true", null, null)
        );

        verify(activeScanService).stopActiveScanJob("A-orphan");
        ScanJob queuedJob = failingStore.list().getFirst();
        assertEquals(ScanJobStatus.QUEUED, queuedJob.getStatus());
    }

    private String extractJobId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Job ID: ")) {
                return line.substring("Job ID: ".length()).trim();
            }
        }
        fail("Unable to extract job ID from response: " + response);
        return null;
    }

    private String extractValueByPrefix(String response, String prefix) {
        for (String line : response.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        fail("Unable to extract value with prefix '" + prefix + "' from response: " + response);
        return null;
    }

    private static final class FailingUpdateScanJobStore extends InMemoryScanJobStore {
        @Override
        public Optional<ScanJob> updateClaimedJob(
                String jobId,
                mcp.server.zap.core.service.queue.ScanJobClaimToken claimToken,
                Instant now,
                UnaryOperator<ScanJob> updater
        ) {
            throw new IllegalStateException("durable write failed");
        }
    }

    private static final class SharedLeadershipState {
        private final AtomicReference<String> leaderId;

        private SharedLeadershipState(String initialLeaderId) {
            this.leaderId = new AtomicReference<>(initialLeaderId);
        }

        private void setLeaderId(String nextLeaderId) {
            leaderId.set(nextLeaderId);
        }

        private String getLeaderId() {
            return leaderId.get();
        }
    }

    private static final class TestQueueLeadershipCoordinator implements QueueLeadershipCoordinator {
        private final String nodeId;
        private final SharedLeadershipState state;
        private volatile boolean wasLeader;

        private TestQueueLeadershipCoordinator(String nodeId, SharedLeadershipState state) {
            this.nodeId = nodeId;
            this.state = state;
        }

        @Override
        public LeadershipDecision evaluateLeadership() {
            boolean isLeaderNow = nodeId.equals(state.getLeaderId());
            boolean acquired = !wasLeader && isLeaderNow;
            boolean lost = wasLeader && !isLeaderNow;
            wasLeader = isLeaderNow;
            return new LeadershipDecision(isLeaderNow, acquired, lost);
        }

        @Override
        public String nodeId() {
            return nodeId;
        }
    }
}
