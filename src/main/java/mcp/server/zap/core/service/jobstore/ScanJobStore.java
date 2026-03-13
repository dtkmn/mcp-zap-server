package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface ScanJobStore {

    void upsertAll(Collection<ScanJob> jobs);

    Optional<ScanJob> load(String jobId);

    List<ScanJob> list();

    /**
     * Atomically update durable job state and return the committed rows.
     */
    List<ScanJob> updateAndGet(UnaryOperator<List<ScanJob>> updater);
}
