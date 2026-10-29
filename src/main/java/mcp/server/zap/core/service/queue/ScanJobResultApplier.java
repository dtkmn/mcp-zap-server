package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
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

            // NativeScanLaunch.accepted already persisted this exact receipt at the
            // gateway boundary. Batch result application never adopts or stops it again.
            if (result.success()) continue;

            Instant appliedAt = Instant.now();
            try {
                scanJobStore.updateClaimedJob(result.jobId(), result.claimToken(), appliedAt, job -> {
                    if (job.getStatus() != ScanJobStatus.QUEUED) return job;

                    if (result.engineBusy()) {
                        if (job.isCancellationRequested()) {
                            // Explicitly rejected before execution: there is no owned crawl to stop.
                            job.markCancelled();
                        } else {
                            deferBusyEngine(job, result.error(), appliedAt);
                        }
                        return job;
                    }
                    // Only a failure before beforeDispatch can still be QUEUED here.
                    // Possible native execution is already STARTING/UNKNOWN and is excluded.
                    job.incrementAttempts();
                    if (job.isCancellationRequested()) {
                        job.markCancelled();
                    } else if (scheduleStartRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after preparation error", job.getId());
                    } else {
                        log.error("Failed to prepare scan job {}: {}", job.getId(), result.error());
                    }
                    return job;
                }).orElse(null);
            } catch (RuntimeException e) {
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
        }

        return new ScanJobApplyOutcome(List.of(), firstPersistenceFailure);
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

}
