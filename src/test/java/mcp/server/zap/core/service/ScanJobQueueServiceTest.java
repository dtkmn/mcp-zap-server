package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScanJobQueueServiceTest {

    private ActiveScanService activeScanService;
    private SpiderScanService spiderScanService;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;
    private ScanJobQueueService service;

    @BeforeEach
    void setup() {
        activeScanService = mock(ActiveScanService.class);
        spiderScanService = mock(SpiderScanService.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(1);
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(1);

        service = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false
        );
    }

    private ScanJobQueueService newServiceWithPolicies(
            ScanJobQueueService.RetryPolicy activePolicy,
            ScanJobQueueService.RetryPolicy spiderPolicy
    ) {
        return new ScanJobQueueService(
                activeScanService,
                spiderScanService,
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

        String response = service.queueActiveScan("http://example.com", null, null);
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

        String firstResponse = service.queueActiveScan("http://example.com/1", "true", null);
        String secondResponse = service.queueActiveScan("http://example.com/2", "true", null);

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

        String runningJobId = extractJobId(writerService.queueActiveScan("http://example.com/restore-1", "true", null));
        String queuedJobId = extractJobId(writerService.queueActiveScan("http://example.com/restore-2", "true", null));

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
    void followerReplicaAcceptsQueueAdmissionAndLeaderDispatchesIt() {
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

        String response = followerService.queueActiveScan("http://example.com/follower", "true", null);
        String jobId = extractJobId(response);

        ScanJob followerJob = followerService.getJobForTesting(jobId);
        assertNotNull(followerJob);
        assertEquals(ScanJobStatus.QUEUED, followerJob.getStatus());
        verify(activeScanService, never()).startActiveScanJob(anyString(), anyString(), any());

        leaderService.processQueueOnceForTesting();

        ScanJob leaderJob = leaderService.getJobForTesting(jobId);
        assertNotNull(leaderJob);
        assertEquals(ScanJobStatus.RUNNING, leaderJob.getStatus());
        assertEquals("A-follower", leaderJob.getZapScanId());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
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

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/cancel", "true", null));
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

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/retry", "true", null));
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

        String sourceJobId = extractJobId(leaderService.queueActiveScan("http://example.com/dead-letter-follower", "true", null));
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

        String jobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store", "true", null));

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

        String firstJobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store-q1", "true", null));
        String secondJobId = extractJobId(storeBackedService.queueActiveScan("http://example.com/store-q2", "true", null));

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

        String queuedJobId = extractJobId(leaderService.queueActiveScan("http://example.com/failover", "true", null));
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
    void cancelQueuedJobMarksCancelled() {
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        String response = service.queueActiveScan("http://example.com", "true", null);
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

        String response = service.queueActiveScan("http://example.com", "true", null);
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

        String response = service.queueActiveScan("http://example.com", "true", null);
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

        String response = delayedService.queueActiveScan("http://example.com", "true", null);
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

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider"));

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

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider"));

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

        String failedJobId = extractJobId(policyService.queueActiveScan("http://example.com/dead-letter", "true", null));

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

        String sourceJobId = extractJobId(policyService.queueActiveScan("http://example.com/requeue", "true", null));
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
    }
}
