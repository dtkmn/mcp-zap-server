package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

public class InMemoryScanJobStore implements ScanJobStore {

    private static final Comparator<ScanJob> JOB_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    private final ConcurrentHashMap<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

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
}
