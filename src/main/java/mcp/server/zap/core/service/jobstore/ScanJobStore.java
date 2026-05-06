package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface ScanJobStore {

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
