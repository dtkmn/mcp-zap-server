package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class InMemoryScanJobStore implements ScanJobStore {

    private static final Comparator<ScanJob> JOB_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    private final ConcurrentHashMap<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public ScanJob admitQueuedJob(ScanJob candidate) {
        lock.lock();
        try {
            ScanJob existing = findByRequesterAndIdempotencyKey(candidate.getRequesterId(), candidate.getIdempotencyKey());
            if (existing != null) {
                return existing;
            }

            candidate.assignQueuePosition(nextQueuePosition());
            jobs.put(candidate.getId(), candidate);
            return candidate;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void upsertAll(Collection<ScanJob> jobs) {
        if (jobs == null) {
            return;
        }
        for (ScanJob job : jobs) {
            if (job != null) {
                this.jobs.put(job.getId(), job);
            }
        }
    }

    @Override
    public Optional<ScanJob> load(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public Optional<ScanJob> loadByRequesterAndIdempotencyKey(String requesterId, String idempotencyKey) {
        return Optional.ofNullable(findByRequesterAndIdempotencyKey(requesterId, idempotencyKey));
    }

    @Override
    public List<ScanJob> claimRunningJobs(String workerId, Instant now, Instant claimUntil) {
        lock.lock();
        try {
            ArrayList<ScanJob> claimed = new ArrayList<>();
            for (ScanJob job : list()) {
                if (job.getStatus() != ScanJobStatus.RUNNING || job.getZapScanId() == null || job.getZapScanId().isBlank()) {
                    continue;
                }
                if (job.isClaimedBy(workerId) || job.getClaimOwnerId() == null || job.claimExpiredAt(now)) {
                    job.claim(workerId, now, claimUntil);
                    claimed.add(job);
                }
            }
            claimed.sort(JOB_ORDER);
            return claimed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ScanJob> claimQueuedJobs(
            String workerId,
            Instant now,
            Instant claimUntil,
            int maxConcurrentActiveScans,
            int maxConcurrentSpiderScans
    ) {
        lock.lock();
        try {
            int activeSlotsRemaining = Math.max(0, maxConcurrentActiveScans - countActiveCapacityInUse(now));
            int spiderSlotsRemaining = Math.max(0, maxConcurrentSpiderScans - countSpiderCapacityInUse(now));
            boolean ajaxBusy = hasAjaxCapacityInUse(now);

            ArrayList<ScanJob> candidates = new ArrayList<>(jobs.values());
            candidates.sort(Comparator
                    .comparingInt((ScanJob job) -> job.getQueuePosition() > 0 ? job.getQueuePosition() : Integer.MAX_VALUE)
                    .thenComparing(ScanJob::getCreatedAt)
                    .thenComparing(ScanJob::getId));

            ArrayList<ScanJob> claimed = new ArrayList<>();
            for (ScanJob job : candidates) {
                if (job.getStatus() != ScanJobStatus.QUEUED) {
                    continue;
                }
                if (job.getNextAttemptAt() != null && now.isBefore(job.getNextAttemptAt())) {
                    continue;
                }
                if (job.hasLiveClaim(now)) {
                    continue;
                }

                boolean activeFamily = job.getType().isActiveFamily();
                if (activeFamily && activeSlotsRemaining <= 0) {
                    continue;
                }
                if (!activeFamily && spiderSlotsRemaining <= 0) {
                    continue;
                }
                if (job.getType() == ScanJobType.AJAX_SPIDER && ajaxBusy) {
                    continue;
                }

                job.claim(workerId, now, claimUntil);
                claimed.add(job);
                if (activeFamily) {
                    activeSlotsRemaining -= 1;
                } else {
                    spiderSlotsRemaining -= 1;
                }
                if (job.getType() == ScanJobType.AJAX_SPIDER) {
                    ajaxBusy = true;
                }
            }

            claimed.sort(JOB_ORDER);
            return claimed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void renewClaims(String workerId, Collection<String> jobIds, Instant now, Instant claimUntil) {
        if (jobIds == null || jobIds.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            for (String jobId : jobIds) {
                ScanJob job = jobs.get(jobId);
                if (job == null || !job.isClaimedBy(workerId) || job.getStatus().isTerminal()) {
                    continue;
                }
                job.claim(workerId, now, claimUntil);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ScanJob> updateClaimedJob(String jobId, String workerId, UnaryOperator<ScanJob> updater) {
        lock.lock();
        try {
            ScanJob job = jobs.get(jobId);
            if (job == null || !job.isClaimedBy(workerId)) {
                return Optional.empty();
            }

            ScanJobStatus priorStatus = job.getStatus();
            ScanJob updated = updater.apply(job);
            if (updated == null) {
                updated = job;
            }

            normalizeQueuePositionTransition(updated, priorStatus);
            jobs.put(jobId, updated);
            return Optional.of(updated);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ScanJob> list() {
        ArrayList<ScanJob> snapshot = new ArrayList<>(jobs.values());
        snapshot.sort(JOB_ORDER);
        return snapshot;
    }

    @Override
    public List<ScanJob> updateAndGet(UnaryOperator<List<ScanJob>> updater) {
        lock.lock();
        try {
            List<ScanJob> updated = updater.apply(list());
            jobs.clear();
            upsertAll(updated);
            return list();
        } finally {
            lock.unlock();
        }
    }

    private ScanJob findByRequesterAndIdempotencyKey(String requesterId, String idempotencyKey) {
        if (requesterId == null || requesterId.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return jobs.values().stream()
                .filter(job -> requesterId.equals(job.getRequesterId()) && idempotencyKey.equals(job.getIdempotencyKey()))
                .findFirst()
                .orElse(null);
    }

    private int nextQueuePosition() {
        return jobs.values().stream()
                .filter(job -> job.getStatus() == ScanJobStatus.QUEUED)
                .mapToInt(ScanJob::getQueuePosition)
                .max()
                .orElse(0) + 1;
    }

    private int countActiveCapacityInUse(Instant now) {
        return (int) jobs.values().stream()
                .filter(job -> job.getType().isActiveFamily())
                .filter(job -> job.getStatus() == ScanJobStatus.RUNNING
                        || (job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)))
                .count();
    }

    private int countSpiderCapacityInUse(Instant now) {
        return (int) jobs.values().stream()
                .filter(job -> job.getType().isSpiderFamily())
                .filter(job -> job.getStatus() == ScanJobStatus.RUNNING
                        || (job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)))
                .count();
    }

    private boolean hasAjaxCapacityInUse(Instant now) {
        return jobs.values().stream()
                .filter(job -> job.getType() == ScanJobType.AJAX_SPIDER)
                .anyMatch(job -> job.getStatus() == ScanJobStatus.RUNNING
                        || (job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)));
    }

    private void normalizeQueuePositionTransition(ScanJob job, ScanJobStatus priorStatus) {
        if (job.getStatus() != ScanJobStatus.QUEUED) {
            job.assignQueuePosition(0);
            return;
        }
        if (job.getQueuePosition() <= 0) {
            job.assignQueuePosition(nextQueuePosition());
        }
    }
}
