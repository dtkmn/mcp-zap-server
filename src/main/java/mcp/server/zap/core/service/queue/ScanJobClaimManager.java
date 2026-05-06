package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ScanJobClaimManager {
    private static final Logger log = LoggerFactory.getLogger(ScanJobClaimManager.class);

    private final ScanJobStore scanJobStore;
    private final String workerNodeId;
    private final ScanJobClaimMetrics metrics;
    private final Set<String> pollingJobIds = new HashSet<>();
    private final Set<String> startingJobIds = new HashSet<>();

    public ScanJobClaimManager(ScanJobStore scanJobStore, String workerNodeId, ScanJobClaimMetrics metrics) {
        this.scanJobStore = scanJobStore;
        this.workerNodeId = workerNodeId;
        this.metrics = metrics != null ? metrics : ScanJobClaimMetrics.noop();
    }

    public synchronized void resetInFlightClaims() {
        pollingJobIds.clear();
        startingJobIds.clear();
    }

    public synchronized void retainValidInFlightClaims(Map<String, ScanJob> jobs, Instant now) {
        pollingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null
                    || job.getStatus() != ScanJobStatus.RUNNING
                    || !job.isClaimedBy(workerNodeId)
                    || !job.hasLiveClaim(now);
        });
        startingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null
                    || job.getStatus() != ScanJobStatus.QUEUED
                    || !job.isClaimedBy(workerNodeId)
                    || !job.hasLiveClaim(now);
        });
    }

    public void renewInFlightClaims(Instant now, Instant claimUntil) {
        Set<String> inFlightJobIds;
        synchronized (this) {
            if (startingJobIds.isEmpty() && pollingJobIds.isEmpty()) {
                return;
            }
            inFlightJobIds = new HashSet<>(startingJobIds);
            inFlightJobIds.addAll(pollingJobIds);
        }

        int renewedCount = scanJobStore.renewClaims(workerNodeId, inFlightJobIds, now, claimUntil);
        metrics.recordRenewedClaims(renewedCount);
    }

    public ScanJobWorkPlan claimWork(
            Collection<ScanJob> priorJobs,
            int maxConcurrentActiveScans,
            int maxConcurrentSpiderScans,
            Instant now,
            Instant claimUntil
    ) {
        Map<String, ScanJob> priorJobsById = copyJobSnapshot(priorJobs);
        List<ScanJob> runningJobs = scanJobStore.claimRunningJobs(workerNodeId, now, claimUntil);
        List<ScanJob> queuedJobs = scanJobStore.claimQueuedJobs(
                workerNodeId,
                now,
                claimUntil,
                maxConcurrentActiveScans,
                maxConcurrentSpiderScans
        );
        recordClaimObservations(priorJobsById, runningJobs, queuedJobs, now);

        synchronized (this) {
            List<ScanJobPollTarget> pollTargets = new ArrayList<>();
            for (ScanJob job : runningJobs) {
                if (!hasText(job.getZapScanId())) {
                    scanJobStore.updateClaimedJob(job.getId(), ScanJobClaimToken.from(job), now, claimedJob -> {
                        claimedJob.markFailed("Missing ZAP scan ID while job is RUNNING");
                        return claimedJob;
                    });
                    continue;
                }
                if (pollingJobIds.add(job.getId())) {
                    pollTargets.add(new ScanJobPollTarget(
                            job.getId(),
                            job.getType(),
                            job.getZapScanId(),
                            ScanJobClaimToken.from(job)
                    ));
                }
            }

            List<ScanJobStartTarget> startTargets = new ArrayList<>();
            for (ScanJob job : queuedJobs) {
                if (startingJobIds.add(job.getId())) {
                    startTargets.add(new ScanJobStartTarget(
                            job.getId(),
                            job.getType(),
                            job.getParameters(),
                            ScanJobClaimToken.from(job)
                    ));
                }
            }
            return new ScanJobWorkPlan(pollTargets, startTargets);
        }
    }

    public synchronized void releasePollTarget(String jobId) {
        pollingJobIds.remove(jobId);
    }

    public synchronized void releaseStartTarget(String jobId) {
        startingJobIds.remove(jobId);
    }

    public void recordClaimConflict(int count) {
        metrics.recordClaimConflicts(count);
    }

    public void recordLateResultCleanup(int count) {
        metrics.recordLateResultCleanups(count);
    }

    public Collection<ScanJob> copyJobs(Collection<ScanJob> jobs) {
        return copyJobSnapshot(jobs).values();
    }

    private Map<String, ScanJob> copyJobSnapshot(Collection<ScanJob> jobs) {
        Map<String, ScanJob> snapshot = new LinkedHashMap<>();
        if (jobs == null) {
            return snapshot;
        }
        for (ScanJob job : jobs) {
            if (job == null) {
                continue;
            }
            snapshot.put(job.getId(), copyJob(job));
        }
        return snapshot;
    }

    private ScanJob copyJob(ScanJob job) {
        return ScanJob.restore(
                job.getId(),
                job.getType(),
                job.getParameters(),
                job.getCreatedAt(),
                job.getMaxAttempts(),
                job.getRequesterId(),
                job.getIdempotencyKey(),
                job.getStatus(),
                job.getAttempts(),
                job.getZapScanId(),
                job.getLastError(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getNextAttemptAt(),
                job.getLastKnownProgress(),
                job.getQueuePosition(),
                job.getClaimOwnerId(),
                job.getClaimFenceId(),
                job.getClaimHeartbeatAt(),
                job.getClaimExpiresAt()
        );
    }

    private void recordClaimObservations(
            Map<String, ScanJob> priorJobs,
            List<ScanJob> runningJobs,
            List<ScanJob> queuedJobs,
            Instant now
    ) {
        metrics.recordQueuedClaims(queuedJobs.size());

        for (ScanJob runningJob : runningJobs) {
            ScanJob priorJob = priorJobs.get(runningJob.getId());
            if (priorJob == null || priorJob.getStatus() != ScanJobStatus.RUNNING) {
                continue;
            }
            if (hasText(priorJob.getClaimOwnerId()) && !workerNodeId.equals(priorJob.getClaimOwnerId())) {
                metrics.recordRunningRecoveries(1);
                log.info(
                        "Recovered running scan job {} claim from worker {} on worker {} (previous expiry={})",
                        runningJob.getId(),
                        priorJob.getClaimOwnerId(),
                        workerNodeId,
                        priorJob.getClaimExpiresAt()
                );
                continue;
            }
            if (!hasText(priorJob.getClaimOwnerId()) && runningJob.isClaimedBy(workerNodeId)) {
                log.info("Claimed previously unowned running scan job {} on worker {}", runningJob.getId(), workerNodeId);
            }
            if (priorJob.claimExpiredAt(now) && workerNodeId.equals(runningJob.getClaimOwnerId())) {
                metrics.recordExpiredClaimRecoveries(1);
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
