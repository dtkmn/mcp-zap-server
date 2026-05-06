package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScanJobResultApplierTest {

    @Test
    void marksRunningJobSucceededWhenPollCompletes() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-complete", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult("job-complete", ScanJobClaimToken.from(job), true, 100, null)),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob completedJob = store.load("job-complete").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.SUCCEEDED, completedJob.getStatus());
        assertEquals(100, completedJob.getLastKnownProgress());
        assertNull(completedJob.getClaimOwnerId());
    }

    @Test
    void keepsRunningJobClaimedWhenPollFails() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-retry", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        Instant originalClaimExpiresAt = job.getClaimExpiresAt();
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult(
                        "job-retry",
                        ScanJobClaimToken.from(job),
                        false,
                        0,
                        "Runtime status check failed: dispatch timed out"
                )),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob preservedJob = store.load("job-retry").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertNull(outcome.persistenceFailure());
        assertEquals(ScanJobStatus.RUNNING, preservedJob.getStatus());
        assertEquals("active-1", preservedJob.getZapScanId());
        assertEquals("Runtime status check failed: dispatch timed out", preservedJob.getLastError());
        assertNull(preservedJob.getNextAttemptAt());
        assertEquals(0, preservedJob.getLastKnownProgress());
        assertEquals("node-a", preservedJob.getClaimOwnerId());
        assertEquals(originalClaimExpiresAt, preservedJob.getClaimExpiresAt());
    }

    @Test
    void clearsTransientPollErrorWhenRunningPollRecovers() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-recovered", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        applier.applyResults(
                List.of(new ScanJobPollResult("job-recovered", ScanJobClaimToken.from(job), false, 0, "status failed")),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob failedPollJob = store.load("job-recovered").orElseThrow();
        assertEquals("status failed", failedPollJob.getLastError());

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult("job-recovered", ScanJobClaimToken.from(failedPollJob), true, 42, null)),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob recoveredJob = store.load("job-recovered").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.RUNNING, recoveredJob.getStatus());
        assertEquals(42, recoveredJob.getLastKnownProgress());
        assertNull(recoveredJob.getLastError());
    }

    @Test
    void clearsTransientPollErrorWhenPollCompletes() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-completed-after-error", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        applier.applyResults(
                List.of(new ScanJobPollResult(
                        "job-completed-after-error",
                        ScanJobClaimToken.from(job),
                        false,
                        0,
                        "status failed"
                )),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob failedPollJob = store.load("job-completed-after-error").orElseThrow();
        assertEquals("status failed", failedPollJob.getLastError());

        applier.applyResults(
                List.of(new ScanJobPollResult(
                        "job-completed-after-error",
                        ScanJobClaimToken.from(failedPollJob),
                        true,
                        100,
                        null
                )),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob completedJob = store.load("job-completed-after-error").orElseThrow();
        assertEquals(ScanJobStatus.SUCCEEDED, completedJob.getStatus());
        assertNull(completedJob.getLastError());
        assertNull(completedJob.getClaimOwnerId());
    }

    @Test
    void ignoresStalePollResultAfterSameWorkerReclaimedRunningJob() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-stale-poll", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        Instant firstClaimAt = Instant.now().minusSeconds(90);
        job.claim("node-a", firstClaimAt, firstClaimAt.plusSeconds(30));
        ScanJobClaimToken staleToken = ScanJobClaimToken.from(job);
        Instant reclaimedAt = Instant.now().minusSeconds(5);
        job.claim("node-a", reclaimedAt, reclaimedAt.plusSeconds(60));
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult("job-stale-poll", staleToken, true, 100, null)),
                List.of(),
                Instant.now().plusSeconds(30)
        );

        ScanJob unchangedJob = store.load("job-stale-poll").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.RUNNING, unchangedJob.getStatus());
        assertEquals(0, unchangedJob.getLastKnownProgress());
        assertEquals(reclaimedAt, unchangedJob.getClaimHeartbeatAt());
    }

    @Test
    void marksStartSuccessRunningAndRetainsClaim() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-start", ScanJobType.SPIDER_SCAN, 2, "node-a");
        store.upsertAll(List.of(job));

        Instant claimUntil = job.getClaimExpiresAt().plusSeconds(30);
        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(),
                List.of(new ScanJobStartResult(
                        "job-start",
                        ScanJobType.SPIDER_SCAN,
                        ScanJobClaimToken.from(job),
                        true,
                        "spider-1",
                        null
                )),
                claimUntil
        );

        ScanJob runningJob = store.load("job-start").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.RUNNING, runningJob.getStatus());
        assertEquals(1, runningJob.getAttempts());
        assertEquals("spider-1", runningJob.getZapScanId());
        assertEquals("node-a", runningJob.getClaimOwnerId());
        assertEquals(claimUntil, runningJob.getClaimExpiresAt());
    }

    @Test
    void returnsStopRequestForLateStartResultAfterClaimMoved() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-late", ScanJobType.ACTIVE_SCAN, 3, "node-b");
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(),
                List.of(new ScanJobStartResult(
                        "job-late",
                        ScanJobType.ACTIVE_SCAN,
                        new ScanJobClaimToken(
                                "node-a",
                                "stale-fence"
                        ),
                        true,
                        "active-late",
                        null
                )),
                Instant.now().plusSeconds(30)
        );

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-late")), outcome.stopRequests());
        ScanJob unchangedJob = store.load("job-late").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals("node-b", unchangedJob.getClaimOwnerId());
    }

    @Test
    void returnsStopRequestWhenFailFastPersistenceThrowsAfterSuccessfulStart() {
        FailingUpdateScanJobStore store = new FailingUpdateScanJobStore();
        ScanJob job = queuedClaimedJob("job-write-fails", ScanJobType.ACTIVE_SCAN, 3, "node-a");
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(),
                List.of(new ScanJobStartResult(
                        "job-write-fails",
                        ScanJobType.ACTIVE_SCAN,
                        ScanJobClaimToken.from(job),
                        true,
                        "active-orphan",
                        null
                )),
                Instant.now().plusSeconds(30)
        );

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-orphan")), outcome.stopRequests());
        assertNotNull(outcome.persistenceFailure());
        ScanJob unchangedJob = store.load("job-write-fails").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals("node-a", unchangedJob.getClaimOwnerId());
    }

    @Test
    void continuesToCleanupStartedScansWhenPollPersistenceFailsFirst() {
        FailingUpdateScanJobStore store = new FailingUpdateScanJobStore();
        ScanJob runningJob = runningJob("job-poll-write-fails", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        ScanJob queuedJob = queuedClaimedJob("job-start-after-poll-failure", ScanJobType.SPIDER_SCAN, 2, "node-a");
        store.upsertAll(List.of(runningJob, queuedJob));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult(
                        "job-poll-write-fails",
                        ScanJobClaimToken.from(runningJob),
                        true,
                        100,
                        null
                )),
                List.of(new ScanJobStartResult(
                        "job-start-after-poll-failure",
                        ScanJobType.SPIDER_SCAN,
                        ScanJobClaimToken.from(queuedJob),
                        true,
                        "spider-orphan",
                        null
                )),
                Instant.now().plusSeconds(30)
        );

        assertNotNull(outcome.persistenceFailure());
        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.SPIDER_SCAN, "spider-orphan")), outcome.stopRequests());
        assertEquals(ScanJobStatus.QUEUED, store.load("job-start-after-poll-failure").orElseThrow().getStatus());
    }

    @Test
    void acceptsResultAfterLeaseRenewalPreservesClaimFence() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-renewed-result", ScanJobType.ACTIVE_SCAN, 3, "active-1");
        ScanJobClaimToken dispatchedToken = ScanJobClaimToken.from(job);
        store.upsertAll(List.of(job));
        Instant renewedAt = Instant.now();
        Instant renewedUntil = renewedAt.plusSeconds(90);
        store.renewClaims(
                "node-a",
                List.of("job-renewed-result"),
                renewedAt,
                renewedUntil
        );

        ScanJob renewedJob = store.load("job-renewed-result").orElseThrow();
        assertEquals(dispatchedToken, ScanJobClaimToken.from(renewedJob));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(new ScanJobPollResult("job-renewed-result", dispatchedToken, true, 42, null)),
                List.of(),
                renewedAt.plusSeconds(30)
        );

        ScanJob completedJob = store.load("job-renewed-result").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.RUNNING, completedJob.getStatus());
        assertEquals(42, completedJob.getLastKnownProgress());
        assertEquals(renewedUntil, completedJob.getClaimExpiresAt());
    }

    @Test
    void acceptsStartResultAfterLeaseRenewalPreservesClaimFence() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-renewed-start", ScanJobType.ACTIVE_SCAN, 3, "node-a");
        ScanJobClaimToken dispatchedToken = ScanJobClaimToken.from(job);
        store.upsertAll(List.of(job));
        Instant renewedAt = Instant.now();
        Instant renewedUntil = renewedAt.plusSeconds(90);
        store.renewClaims(
                "node-a",
                List.of("job-renewed-start"),
                renewedAt,
                renewedUntil
        );

        ScanJob renewedJob = store.load("job-renewed-start").orElseThrow();
        assertEquals(dispatchedToken, ScanJobClaimToken.from(renewedJob));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(),
                List.of(new ScanJobStartResult(
                        "job-renewed-start",
                        ScanJobType.ACTIVE_SCAN,
                        dispatchedToken,
                        true,
                        "active-renewed",
                        null
                )),
                renewedAt.plusSeconds(30)
        );

        ScanJob runningJob = store.load("job-renewed-start").orElseThrow();
        assertEquals(List.of(), outcome.stopRequests());
        assertEquals(ScanJobStatus.RUNNING, runningJob.getStatus());
        assertEquals("active-renewed", runningJob.getZapScanId());
        assertEquals(renewedUntil, runningJob.getClaimExpiresAt());
    }

    @Test
    void returnsStopRequestForStaleStartResultAfterSameWorkerReclaimedJob() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-stale", ScanJobType.ACTIVE_SCAN, 3, "node-a");
        Instant firstClaimAt = Instant.now().minusSeconds(90);
        job.claim("node-a", firstClaimAt, firstClaimAt.plusSeconds(30));
        ScanJobClaimToken staleToken = ScanJobClaimToken.from(job);
        Instant reclaimedAt = Instant.now().minusSeconds(5);
        job.claim("node-a", reclaimedAt, reclaimedAt.plusSeconds(60));
        store.upsertAll(List.of(job));

        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);

        ScanJobApplyOutcome outcome = applier.applyResults(
                List.of(),
                List.of(new ScanJobStartResult(
                        "job-stale",
                        ScanJobType.ACTIVE_SCAN,
                        staleToken,
                        true,
                        "active-stale",
                        null
                )),
                Instant.now().plusSeconds(30)
        );

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-stale")), outcome.stopRequests());
        ScanJob unchangedJob = store.load("job-stale").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals("node-a", unchangedJob.getClaimOwnerId());
        assertEquals(reclaimedAt, unchangedJob.getClaimHeartbeatAt());
    }

    private ScanJobResultApplier resultApplier(
            InMemoryScanJobStore store,
            String workerNodeId,
            int activeMaxAttempts,
            int spiderMaxAttempts
    ) {
        ScanJobClaimManager claimManager = new ScanJobClaimManager(store, workerNodeId, ScanJobClaimMetrics.noop());
        return new ScanJobResultApplier(
                store,
                workerNodeId,
                claimManager,
                new ScanJobRetryPolicy(activeMaxAttempts, 0, 0, 1.0),
                new ScanJobRetryPolicy(spiderMaxAttempts, 0, 0, 1.0)
        );
    }

    private ScanJob runningJob(String id, ScanJobType type, int maxAttempts, String scanId) {
        Instant claimAt = Instant.now().minusSeconds(5);
        ScanJob job = new ScanJob(
                id,
                type,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                maxAttempts
        );
        job.incrementAttempts();
        job.markRunning(scanId);
        job.claim("node-a", claimAt, claimAt.plusSeconds(60));
        return job;
    }

    private ScanJob queuedClaimedJob(String id, ScanJobType type, int maxAttempts, String workerNodeId) {
        Instant claimAt = Instant.now().minusSeconds(5);
        ScanJob job = new ScanJob(
                id,
                type,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                maxAttempts
        );
        job.claim(workerNodeId, claimAt, claimAt.plusSeconds(60));
        return job;
    }

    private static class FailingUpdateScanJobStore extends InMemoryScanJobStore {
        @Override
        public Optional<ScanJob> updateClaimedJob(
                String jobId,
                ScanJobClaimToken claimToken,
                Instant now,
                UnaryOperator<ScanJob> updater
        ) {
            throw new IllegalStateException("durable write failed");
        }
    }
}
