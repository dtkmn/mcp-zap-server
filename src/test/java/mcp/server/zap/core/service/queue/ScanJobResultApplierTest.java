package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.assertj.core.api.Assertions.assertThat;

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

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-late", job.getId())), outcome.stopRequests());
        ScanJob unchangedJob = store.load("job-late").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals("node-b", unchangedJob.getClaimOwnerId());
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void alreadyPersistedStartDoesNotRequestCleanupAfterClaimMoved(ScanJobType type) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("adopted-before-result", type, 2, "scan-adopted");
        ScanJobStartResult lateResult = ScanJobStartResult.success(startTarget(job), "scan-adopted");
        Instant now = Instant.now();
        job.claim("node-b", now, now.plusSeconds(60));
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2)
                .applyResults(List.of(), List.of(lateResult), now.plusSeconds(60));

        assertTrue(outcome.stopRequests().isEmpty(), "The newer claim still owns the same running scan");
        ScanJob preserved = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, preserved.getStatus());
        assertEquals("scan-adopted", preserved.getZapScanId());
        assertEquals("node-b", preserved.getClaimOwnerId());
        assertEquals(1, preserved.getAttempts());
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void unadoptedStartRequestsCleanupWithJobIdentity(ScanJobType type) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("unadopted-job", type, 2, "node-b");
        store.upsertAll(List.of(job));
        ScanJobStartTarget staleTarget = new ScanJobStartTarget(job.getId(), job.getType(), job.getParameters(),
                new ScanJobClaimToken("node-a", "stale-fence"));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2).applyResults(List.of(),
                List.of(ScanJobStartResult.success(staleTarget, "scan-unadopted")), Instant.now().plusSeconds(60));

        assertEquals(List.of(new ScanJobStopRequest(type, "scan-unadopted", job.getId())),
                outcome.stopRequests());
        ScanJob preserved = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, preserved.getStatus());
        assertEquals("node-b", preserved.getClaimOwnerId());
        assertEquals(0, preserved.getAttempts());
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void ownershipLookupFailurePreservesCleanupRequestsAndProcessesLaterStarts(ScanJobType type) {
        IllegalStateException lookupFailure = new IllegalStateException("ownership lookup unavailable");
        InMemoryScanJobStore store = new InMemoryScanJobStore() {
            @Override
            public Optional<ScanJob> load(String jobId) {
                if ("ownership-lookup-fails".equals(jobId)) {
                    throw lookupFailure;
                }
                return super.load(jobId);
            }
        };
        ScanJob earlier = queuedClaimedJob("active-earlier", ScanJobType.ACTIVE_SCAN, 3, "node-b");
        ScanJob abandoned = queuedClaimedJob("ownership-lookup-fails", type, 2, "node-b");
        ScanJob later = queuedClaimedJob("spider-later", ScanJobType.SPIDER_SCAN, 2, "node-a");
        store.upsertAll(List.of(earlier, abandoned, later));
        ScanJobClaimToken staleToken = new ScanJobClaimToken("node-a", "stale-fence");

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2).applyResults(List.of(), List.of(
                ScanJobStartResult.success(new ScanJobStartTarget(earlier.getId(), earlier.getType(),
                        earlier.getParameters(), staleToken), "active-orphan"),
                ScanJobStartResult.success(new ScanJobStartTarget(abandoned.getId(), abandoned.getType(),
                        abandoned.getParameters(), staleToken), "scan-orphan"),
                ScanJobStartResult.success(startTarget(later), "spider-started")), Instant.now().plusSeconds(60));

        assertEquals(lookupFailure, outcome.persistenceFailure());
        assertEquals(List.of(
                new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-orphan", earlier.getId()),
                new ScanJobStopRequest(type, "scan-orphan", abandoned.getId())), outcome.stopRequests());
        ScanJob started = store.load(later.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, started.getStatus());
        assertEquals("spider-started", started.getZapScanId());
        assertEquals(1, started.getAttempts());
    }

    @ParameterizedTest
    @EnumSource(value = ScanJobType.class, names = "AJAX_SPIDER", mode = EnumSource.Mode.EXCLUDE)
    void lateStartCleanupKeepsNewerRunningScanAndClaimUntouched(ScanJobType type) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("job-with-newer-attempt", type, 3, "scan-newer");
        Instant claimedAt = Instant.now();
        job.claim("node-b", claimedAt, claimedAt.plusSeconds(60));
        ScanJobClaimToken newerClaim = ScanJobClaimToken.from(job);
        store.upsertAll(List.of(job));
        ScanJobStartTarget lateTarget = new ScanJobStartTarget(job.getId(), type, job.getParameters(),
                new ScanJobClaimToken("node-a", "old-fence"));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2).applyResults(List.of(),
                List.of(ScanJobStartResult.success(lateTarget, "scan-old")), claimedAt.plusSeconds(60));

        assertEquals(List.of(new ScanJobStopRequest(type, "scan-old", job.getId())), outcome.stopRequests());
        ScanJob preserved = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, preserved.getStatus());
        assertEquals("scan-newer", preserved.getZapScanId());
        assertEquals(newerClaim, ScanJobClaimToken.from(preserved));
        assertEquals(1, preserved.getAttempts());
        assertFalse(preserved.isCancellationRequested());
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

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-orphan", job.getId())), outcome.stopRequests());
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
        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.SPIDER_SCAN, "spider-orphan", queuedJob.getId())), outcome.stopRequests());
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

        assertEquals(List.of(new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "active-stale", job.getId())), outcome.stopRequests());
        ScanJob unchangedJob = store.load("job-stale").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals("node-a", unchangedJob.getClaimOwnerId());
        assertEquals(reclaimedAt, unchangedJob.getClaimHeartbeatAt());
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void busyDeferralsPreserveAttemptsAndFirstWaitTimeWhileUsingFamilyBackoff(ScanJobType type) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-busy", type, 3, "node-a");
        job.incrementAttempts();
        store.upsertAll(List.of(job));
        ScanJobResultApplier applier = busyResultApplier(store, Duration.ofMinutes(3));
        long initialBackoffMs = type.isActiveFamily() ? 2000 : 1000;

        ScanJobApplyOutcome firstOutcome = applier.applyResults(List.of(),
                List.of(ScanJobStartResult.busy(startTarget(job), "Engine is busy")),
                Instant.now().plusSeconds(30));

        ScanJob waitingJob = store.load(job.getId()).orElseThrow();
        Instant firstWaitAt = waitingJob.getBusyWaitStartedAt();
        assertEquals(ScanJobStatus.QUEUED, waitingJob.getStatus());
        assertEquals(1, waitingJob.getAttempts());
        assertEquals(1, waitingJob.getBusyWaitCount());
        assertNotNull(firstWaitAt);
        assertEquals(firstWaitAt.plusMillis(initialBackoffMs), waitingJob.getNextAttemptAt());
        assertEquals("Engine is busy", waitingJob.getLastError());
        assertNull(waitingJob.getClaimOwnerId());
        assertNull(waitingJob.getClaimFenceId());
        assertEquals(List.of(), firstOutcome.stopRequests());

        Instant beforeSecondResult = Instant.now();
        waitingJob.claim("node-a", beforeSecondResult, beforeSecondResult.plusSeconds(30));
        applier.applyResults(List.of(),
                List.of(ScanJobStartResult.busy(startTarget(waitingJob), "Engine is still busy")),
                beforeSecondResult.plusSeconds(30));
        Instant afterSecondResult = Instant.now();

        ScanJob stillWaitingJob = store.load(job.getId()).orElseThrow();
        assertEquals(1, stillWaitingJob.getAttempts());
        assertEquals(2, stillWaitingJob.getBusyWaitCount());
        assertEquals(firstWaitAt, stillWaitingJob.getBusyWaitStartedAt());
        assertThat(stillWaitingJob.getNextAttemptAt()).isBetween(
                beforeSecondResult.plusMillis(initialBackoffMs * 2),
                afterSecondResult.plusMillis(initialBackoffMs * 2));
        assertNull(stillWaitingJob.getClaimOwnerId());
    }

    @Test
    void startAfterBusyDeferralConsumesOnlyTheRealAttemptAndClearsWaitingState() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-ready", ScanJobType.AJAX_SPIDER, 2, "node-a");
        store.upsertAll(List.of(job));
        ScanJobResultApplier applier = busyResultApplier(store, Duration.ofMinutes(3));
        applier.applyResults(List.of(),
                List.of(ScanJobStartResult.busy(startTarget(job), "Engine is busy")),
                Instant.now().plusSeconds(30));

        ScanJob waitingJob = store.load(job.getId()).orElseThrow();
        assertEquals(0, waitingJob.getAttempts());
        Instant now = Instant.now();
        waitingJob.claim("node-a", now, now.plusSeconds(30));
        applier.applyResults(List.of(),
                List.of(ScanJobStartResult.success(startTarget(waitingJob), "ajax-spider:1")),
                now.plusSeconds(30));

        ScanJob startedJob = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, startedJob.getStatus());
        assertEquals(1, startedJob.getAttempts());
        assertEquals("ajax-spider:1", startedJob.getZapScanId());
        assertNull(startedJob.getBusyWaitStartedAt());
        assertEquals(0, startedJob.getBusyWaitCount());
        assertNull(startedJob.getNextAttemptAt());
        assertNull(startedJob.getLastError());
        assertEquals("node-a", startedJob.getClaimOwnerId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 3000})
    void busyTimeoutFailsWithoutConsumingStartupAttempts(long maximumWaitMs) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-timeout", ScanJobType.AJAX_SPIDER, 2, "node-a");
        job.incrementAttempts();
        if (maximumWaitMs > 0) {
            Instant now = Instant.now();
            job.markWaitingForEngine(now.minusSeconds(10), now, "Engine is busy");
            job.claim("node-a", now, now.plusSeconds(30));
        }
        store.upsertAll(List.of(job));
        ScanJobResultApplier applier = busyResultApplier(store, Duration.ofMillis(maximumWaitMs));

        ScanJobApplyOutcome outcome = applier.applyResults(List.of(),
                List.of(ScanJobStartResult.busy(startTarget(job), "Engine is still busy")),
                Instant.now().plusSeconds(30));

        ScanJob failedJob = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.FAILED, failedJob.getStatus());
        assertEquals(1, failedJob.getAttempts());
        assertThat(failedJob.getLastError()).contains("Engine busy wait timed out after " + maximumWaitMs + " ms",
                "Engine is still busy");
        assertNull(failedJob.getNextAttemptAt());
        assertNull(failedJob.getClaimOwnerId());
        assertEquals(List.of(), outcome.stopRequests());
    }

    @Test
    void lateBusyResultDoesNotReviveCancelledJob() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("job-cancelled", ScanJobType.AJAX_SPIDER, 2, "node-a");
        ScanJobStartResult lateResult = ScanJobStartResult.busy(startTarget(job), "Engine is busy");
        job.markCancelled();
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = busyResultApplier(store, Duration.ofMinutes(3))
                .applyResults(List.of(), List.of(lateResult), Instant.now().plusSeconds(30));

        ScanJob cancelledJob = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.CANCELLED, cancelledJob.getStatus());
        assertEquals(0, cancelledJob.getAttempts());
        assertNull(cancelledJob.getBusyWaitStartedAt());
        assertNull(cancelledJob.getNextAttemptAt());
        assertEquals(List.of(), outcome.stopRequests());
    }

    @Test
    void pollDispatchedBeforeCancellationCannotCompletePendingCancellation() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("ajax-pending-poll", ScanJobType.AJAX_SPIDER, 2, "ajax-spider:1");
        ScanJobPollResult stalePoll = new ScanJobPollResult(job.getId(), ScanJobClaimToken.from(job), true, 100, null);
        Instant now = Instant.now();
        job.requestCancellation(now, now.plusSeconds(30));
        job.scheduleCancellationRetry(now.plusSeconds(1), "Stop not yet accepted");
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2)
                .applyResults(List.of(stalePoll), List.of(), now.plusSeconds(60));

        ScanJob preserved = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, preserved.getStatus());
        assertTrue(preserved.isCancellationPending());
        assertEquals(now.plusSeconds(30), preserved.getCancelDeadlineAt());
        assertEquals("Stop not yet accepted", preserved.getLastError());
        assertEquals(0, preserved.getLastKnownProgress());
        assertEquals("node-a", preserved.getClaimOwnerId());
        assertTrue(outcome.stopRequests().isEmpty());
    }

    @Test
    void successfulLaunchKeepsCancellationRequestedDuringStartup() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("ajax-cancel-start", ScanJobType.AJAX_SPIDER, 2, "node-a");
        ScanJobStartResult startResult = ScanJobStartResult.success(startTarget(job), "ajax-spider:1");
        Instant now = Instant.now();
        job.requestCancellation(now, now.plusSeconds(30));
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2)
                .applyResults(List.of(), List.of(startResult), now.plusSeconds(60));

        ScanJob started = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, started.getStatus());
        assertEquals("ajax-spider:1", started.getZapScanId());
        assertEquals(1, started.getAttempts());
        assertTrue(started.isCancellationPending());
        assertEquals(now, started.getCancelRequestedAt());
        assertEquals(now.plusSeconds(30), started.getCancelDeadlineAt());
        assertEquals(now, started.getCancelNextAttemptAt());
        assertTrue(outcome.stopRequests().isEmpty(), "The durable cancellation request owns further stop attempts");
    }

    @Test
    void duplicateStartResultDoesNotCleanUpAlreadyAdoptedScan() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("ajax-already-adopted", ScanJobType.AJAX_SPIDER, 2, "ajax-spider:1");
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2).applyResults(List.of(),
                List.of(ScanJobStartResult.success(startTarget(job), "ajax-spider:1")),
                Instant.now().plusSeconds(60));

        assertEquals(ScanJobStatus.RUNNING, store.load(job.getId()).orElseThrow().getStatus());
        assertEquals(1, job.getAttempts());
        assertTrue(outcome.stopRequests().isEmpty());
    }

    @Test
    void cancellationAfterAdoptionDoesNotTriggerCleanupOfSuccessfulAjaxStart() {
        InMemoryScanJobStore store = new InMemoryScanJobStore() {
            @Override
            public Optional<ScanJob> updateClaimedJob(String jobId, ScanJobClaimToken claimToken,
                                                       Instant now, UnaryOperator<ScanJob> updater) {
                Optional<ScanJob> adopted = super.updateClaimedJob(jobId, claimToken, now, updater);
                // A concurrent cancel can mutate the shared row before the caller receives it.
                adopted.ifPresent(ScanJob::markCancelled);
                return adopted;
            }
        };
        ScanJob job = queuedClaimedJob("ajax-cancel-after-adoption", ScanJobType.AJAX_SPIDER, 2, "node-a");
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2).applyResults(List.of(),
                List.of(ScanJobStartResult.success(startTarget(job), "ajax-spider:1")),
                Instant.now().plusSeconds(60));

        assertEquals(ScanJobStatus.CANCELLED, store.load(job.getId()).orElseThrow().getStatus());
        assertEquals(1, job.getAttempts());
        assertNull(outcome.persistenceFailure());
        assertTrue(outcome.stopRequests().isEmpty(), "An adopted and cancelled crawl must not stop a later crawl");
    }

    @Test
    void explicitBusyLaunchConfirmsCancellationWithoutSendingGlobalStop() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("ajax-cancel-rejected", ScanJobType.AJAX_SPIDER, 2, "node-a");
        ScanJobStartResult rejected = ScanJobStartResult.busy(startTarget(job), "scan_in_progress");
        Instant now = Instant.now();
        job.requestCancellation(now, now.plusSeconds(30));
        store.upsertAll(List.of(job));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2)
                .applyResults(List.of(), List.of(rejected), now.plusSeconds(60));

        ScanJob cancelled = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.CANCELLED, cancelled.getStatus());
        assertFalse(cancelled.isCancellationRequested());
        assertEquals(0, cancelled.getAttempts());
        assertNull(cancelled.getClaimOwnerId());
        assertNull(cancelled.getCancelNextAttemptAt());
        assertTrue(outcome.stopRequests().isEmpty(), "A rejected launch must not stop somebody else's crawl");
    }

    @Test
    void ambiguousLaunchFailurePreservesCancellationAndAjaxReservation() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = queuedClaimedJob("ajax-cancel-ambiguous", ScanJobType.AJAX_SPIDER, 2, "node-a");
        ScanJobStartResult failed = ScanJobStartResult.failure(startTarget(job), "dispatch timed out");
        Instant now = Instant.now();
        job.requestCancellation(now, now.plusSeconds(30));
        ScanJob next = new ScanJob("ajax-next", ScanJobType.AJAX_SPIDER, Map.of(), now, 2);
        store.upsertAll(List.of(job, next));

        ScanJobApplyOutcome outcome = resultApplier(store, "node-a", 3, 2)
                .applyResults(List.of(), List.of(failed), now.plusSeconds(60));

        ScanJob preserved = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, preserved.getStatus());
        assertTrue(preserved.isCancellationPending());
        assertEquals(now.plusSeconds(30), preserved.getCancelDeadlineAt());
        assertEquals(1, preserved.getAttempts());
        assertNull(preserved.getClaimOwnerId());
        assertNull(preserved.getNextAttemptAt());
        assertThat(preserved.getLastError()).contains("Cancellation pending", "dispatch timed out");
        assertTrue(store.claimQueuedJobs("node-b", now.plusSeconds(1), now.plusSeconds(60), 1, 3).isEmpty());
        assertTrue(outcome.stopRequests().isEmpty());
    }

    @Test
    void unconfirmedCancellationSurvivesPollingErrorsUntilStoppedIsObserved() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = runningJob("ajax-unconfirmed", ScanJobType.AJAX_SPIDER, 2, "ajax-spider:1");
        Instant now = Instant.now();
        job.requestCancellation(now.minusSeconds(40), now.minusSeconds(10));
        job.scheduleCancellationRetry(null, "Unable to confirm cancellation; scan may still be running");
        store.upsertAll(List.of(job));
        ScanJobResultApplier applier = resultApplier(store, "node-a", 3, 2);
        ScanJobClaimToken token = ScanJobClaimToken.from(job);

        applier.applyResults(List.of(new ScanJobPollResult(job.getId(), token, false, 0, "status unavailable")),
                List.of(), now.plusSeconds(60));

        ScanJob unconfirmed = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, unconfirmed.getStatus());
        assertTrue(unconfirmed.isCancellationRequested());
        assertFalse(unconfirmed.isCancellationPending());
        assertEquals("Unable to confirm cancellation; scan may still be running", unconfirmed.getLastError());
        assertEquals(1, unconfirmed.getCancelAttemptCount());

        applier.applyResults(List.of(new ScanJobPollResult(job.getId(), token, true, 100, null)),
                List.of(), now.plusSeconds(60));

        ScanJob cancelled = store.load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.CANCELLED, cancelled.getStatus());
        assertFalse(cancelled.isCancellationRequested());
        assertNull(cancelled.getLastError());
        assertNull(cancelled.getClaimOwnerId());
    }

    private ScanJobStartTarget startTarget(ScanJob job) {
        return new ScanJobStartTarget(job.getId(), job.getType(), job.getParameters(), ScanJobClaimToken.from(job));
    }

    private ScanJobResultApplier busyResultApplier(InMemoryScanJobStore store, Duration maximumWait) {
        return new ScanJobResultApplier(store, "node-a",
                new ScanJobClaimManager(store, "node-a", ScanJobClaimMetrics.noop()),
                new ScanJobRetryPolicy(3, 2000, 8000, 2),
                new ScanJobRetryPolicy(2, 1000, 4000, 2), maximumWait);
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
