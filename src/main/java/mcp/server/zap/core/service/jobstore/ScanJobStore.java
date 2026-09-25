package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

public interface ScanJobStore {

    /**
     * Serialize AJAX start/stop side effects across workers sharing this store.
     * Returns empty if another lifecycle action owns the lock. The callback receives
     * a strict current snapshot without holding row or queue-state locks and runs at most once.
     */
    <T> Optional<T> tryWithAjaxLifecycleLock(Function<List<ScanJob>, T> action);

    ScanJob admitQueuedJob(ScanJob candidate);

    void upsertAll(Collection<ScanJob> jobs);

    Optional<ScanJob> load(String jobId);

    Optional<ScanJob> loadByRequesterAndIdempotencyKey(String requesterId, String idempotencyKey);

    List<ScanJob> claimRunningJobs(String workerId, Instant now, Instant claimUntil);

    List<ScanJob> claimQueuedJobs(
            String workerId,
            Instant now,
            Instant claimUntil,
            int maxConcurrentActiveScans,
            int maxConcurrentSpiderScans
    );

    int renewClaims(String workerId, Collection<String> jobIds, Instant now, Instant claimUntil);

    Optional<ScanJob> updateClaimedJob(
            String jobId,
            ScanJobClaimToken claimToken,
            Instant now,
            UnaryOperator<ScanJob> updater
    );

    List<ScanJob> list();

    /**
     * Atomically update durable job state and return the committed rows.
     */
    List<ScanJob> updateAndGet(UnaryOperator<List<ScanJob>> updater);
}
