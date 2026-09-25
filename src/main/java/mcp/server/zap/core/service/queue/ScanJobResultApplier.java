package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ScanJobResultApplier {
    private static final Logger log = LoggerFactory.getLogger(ScanJobResultApplier.class);

    private final ScanJobStore scanJobStore;
    private final String workerNodeId;
    private final ScanJobClaimManager claimManager;
    private final ScanJobRetryPolicy activeRetryPolicy;
    private final ScanJobRetryPolicy spiderRetryPolicy;
    private final Duration engineBusyMaxWait;

    public ScanJobResultApplier(ScanJobStore scanJobStore,
                                String workerNodeId,
                                ScanJobClaimManager claimManager,
                                ScanJobRetryPolicy activeRetryPolicy,
                                ScanJobRetryPolicy spiderRetryPolicy) {
        this(scanJobStore, workerNodeId, claimManager, activeRetryPolicy, spiderRetryPolicy, Duration.ofMinutes(3));
    }

    public ScanJobResultApplier(ScanJobStore scanJobStore,
                                String workerNodeId,
                                ScanJobClaimManager claimManager,
                                ScanJobRetryPolicy activeRetryPolicy,
                                ScanJobRetryPolicy spiderRetryPolicy,
                                Duration engineBusyMaxWait) {
        this.scanJobStore = scanJobStore;
        this.workerNodeId = workerNodeId;
        this.claimManager = claimManager;
        this.activeRetryPolicy = activeRetryPolicy;
        this.spiderRetryPolicy = spiderRetryPolicy;
        if (engineBusyMaxWait.isNegative()) {
            throw new IllegalArgumentException("engineBusyMaxWait must not be negative");
        }
        this.engineBusyMaxWait = engineBusyMaxWait;
    }

    public ScanJobApplyOutcome applyResults(
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults,
            Instant claimUntil
    ) {
        List<ScanJobStopRequest> stopRequests = new ArrayList<>();
        RuntimeException firstPersistenceFailure = null;
        for (ScanJobPollResult result : pollResults) {
            claimManager.releasePollTarget(result.jobId());

            Instant appliedAt = Instant.now();
            ScanJob updatedJob;
            boolean pollPersistenceFailure = false;
            try {
                updatedJob = scanJobStore.updateClaimedJob(result.jobId(), result.claimToken(), appliedAt, job -> {
                    if (job.getStatus() != ScanJobStatus.RUNNING) {
                        return job;
                    }

                    if (job.isCancellationRequested()) {
                        // A status result dispatched before cancellation cannot free the AJAX
                        // slot while a global stop is still pending. After retries end, continue
                        // observing the engine without losing the unconfirmed-cancellation error.
                        if (!job.isCancellationPending() && result.success() && result.progress() >= 100) {
                            job.markCancelled();
                        } else {
                            renewClaimWithoutShortening(job, appliedAt, claimUntil);
                        }
                        return job;
                    }

                    if (!result.success()) {
                        job.recordTransientError(result.error());
                        renewClaimWithoutShortening(job, appliedAt, claimUntil);
                        log.warn(
                                "Preserving running scan job {} after status refresh error on worker {}: {}",
                                job.getId(),
                                workerNodeId,
                                result.error()
                        );
                        return job;
                    }

                    job.updateProgress(result.progress());
                    if (result.progress() >= 100) {
                        job.markSucceeded(result.progress());
                        log.info("Scan job {} completed successfully", job.getId());
                    } else {
                        renewClaimWithoutShortening(job, appliedAt, claimUntil);
                    }
                    return job;
                }).orElse(null);
            } catch (RuntimeException e) {
                updatedJob = null;
                pollPersistenceFailure = true;
                if (firstPersistenceFailure == null) {
                    firstPersistenceFailure = e;
                }
                log.error(
                        "Failed to persist scan poll result for job {} on worker {}: {}",
                        result.jobId(),
                        workerNodeId,
                        e.getMessage(),
                        e
                );
            }

            if (updatedJob == null && !pollPersistenceFailure) {
                claimManager.recordClaimConflict(1);
                log.debug("Skipping polling result for job {} because this replica no longer owns the claim", result.jobId());
            }
        }

        for (ScanJobStartResult result : startResults) {
            claimManager.releaseStartTarget(result.jobId());

            Instant appliedAt = Instant.now();
            RuntimeException persistenceFailure = null;
            ScanJob updatedJob = null;
            boolean[] adoptedStart = {false};
            try {
                updatedJob = scanJobStore.updateClaimedJob(result.jobId(), result.claimToken(), appliedAt, job -> {
                    adoptedStart[0] = false;
                    if (job.getStatus() != ScanJobStatus.QUEUED) {
                        adoptedStart[0] = job.getStatus() == ScanJobStatus.RUNNING
                                && result.success() && result.scanId() != null
                                && result.scanId().equals(job.getZapScanId());
                        return job;
                    }

                    if (result.engineBusy()) {
                        if (job.isCancellationRequested()) {
                            // Explicitly rejected before execution: there is no owned crawl to stop.
                            job.markCancelled();
                        } else {
                            deferBusyEngine(job, result.error(), appliedAt);
                        }
                        return job;
                    }
                    job.incrementAttempts();
                    if (result.success()) {
                        job.markRunning(result.scanId());
                        renewClaimWithoutShortening(job, appliedAt, claimUntil);
                        adoptedStart[0] = true;
                        log.info("Started scan job {} as ZAP scan {} on worker {}", job.getId(), result.scanId(), workerNodeId);
                    } else {
                        if (job.isCancellationRequested()) {
                            // Startup may have reached ZAP (e.g. a dispatch timeout). Preserve
                            // ownership for cancellation instead of launching another crawl.
                            job.recordTransientError("Cancellation pending after startup error: " + result.error());
                            job.clearClaim();
                            return job;
                        }
                        if (scheduleStartRetryIfAllowed(job, result.error())) {
                            log.info("Scheduled retry for scan job {} after startup error", job.getId());
                        } else {
                            log.error("Failed to start scan job {}: {}", job.getId(), result.error());
                        }
                    }
                    return job;
                }).orElse(null);
            } catch (RuntimeException e) {
                persistenceFailure = e;
                if (firstPersistenceFailure == null) {
                    firstPersistenceFailure = e;
                }
                log.error(
                        "Failed to persist scan start result for job {} on worker {}: {}",
                        result.jobId(),
                        workerNodeId,
                        e.getMessage(),
                        e
                );
            }

            // In-memory rows are mutable after commit; a later cancellation must not
            // make an adopted start look abandoned and trigger a second global stop.
            if (result.success() && hasText(result.scanId())
                    && (persistenceFailure != null
                    || updatedJob == null
                    || !adoptedStart[0])) {
                try {
                    if (scanJobStore.load(result.jobId()).filter(job -> job.getStatus() == ScanJobStatus.RUNNING
                            && result.scanId().equals(job.getZapScanId())).isPresent()) {
                        // Persisted ownership can outlive the dispatch claim when another
                        // worker has already taken over polling the same scan.
                        continue;
                    }
                } catch (RuntimeException e) {
                    if (firstPersistenceFailure == null) {
                        firstPersistenceFailure = e;
                    }
                    persistenceFailure = e;
                    log.error("Failed to read accepted scan ownership for job {} on worker {}",
                            result.jobId(), workerNodeId, e);
                }
                claimManager.recordLateResultCleanup(1);
                if (updatedJob == null && persistenceFailure == null) {
                    claimManager.recordClaimConflict(1);
                }
                if (persistenceFailure != null) {
                    log.warn(
                            "Cleaning up scan start result for job {} on worker {} because durable persistence failed",
                            result.jobId(),
                            workerNodeId
                    );
                } else {
                    log.warn(
                            "Cleaning up late scan start result for job {} on worker {} because durable claim ownership moved",
                            result.jobId(),
                            workerNodeId
                    );
                }
                stopRequests.add(new ScanJobStopRequest(result.type(), result.scanId(), result.jobId()));
            }
        }

        return new ScanJobApplyOutcome(stopRequests, firstPersistenceFailure);
    }

    private void deferBusyEngine(ScanJob job, String reason, Instant now) {
        Instant waitingSince = job.getBusyWaitStartedAt() == null ? now : job.getBusyWaitStartedAt();
        Instant deadline = waitingSince.plus(engineBusyMaxWait);
        if (!now.isBefore(deadline)) {
            job.markFailed("Engine busy wait timed out after " + engineBusyMaxWait.toMillis() + " ms: " + reason);
            return;
        }
        long delayMs = policyFor(job.getType()).computeDelayMs(job.getBusyWaitCount() + 1);
        Instant retryAt = now.plusMillis(delayMs);
        job.markWaitingForEngine(now, retryAt.isAfter(deadline) ? deadline : retryAt, reason);
        log.info("Scan job {} is waiting for the engine; next check at {}", job.getId(), job.getNextAttemptAt());
    }

    private void renewClaimWithoutShortening(ScanJob job, Instant heartbeatAt, Instant claimUntil) {
        Instant currentClaimUntil = job.getClaimExpiresAt();
        Instant effectiveClaimUntil = claimUntil;
        if (currentClaimUntil != null && (effectiveClaimUntil == null || currentClaimUntil.isAfter(effectiveClaimUntil))) {
            effectiveClaimUntil = currentClaimUntil;
        }
        job.claim(workerNodeId, heartbeatAt, effectiveClaimUntil);
    }

    private boolean scheduleStartRetryIfAllowed(ScanJob job, String reason) {
        ScanJobRetryPolicy retryPolicy = policyFor(job.getType());
        if (job.getAttempts() >= retryPolicy.maxAttempts()) {
            job.markFailed(reason);
            return false;
        }

        long delayMs = retryPolicy.computeDelayMs(job.getAttempts());
        Instant retryAt = Instant.now().plusMillis(delayMs);
        job.markQueuedForRetry(retryAt, reason);
        return true;
    }

    private ScanJobRetryPolicy policyFor(ScanJobType type) {
        if (type.isActiveFamily()) {
            return activeRetryPolicy;
        }
        return spiderRetryPolicy;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
