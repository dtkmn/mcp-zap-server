package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryScanJobStoreTest {

    @Test
    void ajaxLifecycleSnapshotStaysStableWhileAnotherWorkerUpdatesCancellation() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        Instant now = Instant.now();
        ScanJob job = new ScanJob("ajax-job", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com"), now, 2);
        job.markRunning("ajax-scan");
        job.requestCancellation(now, now.plusSeconds(30));
        job.claim("worker-1", now, now.plusSeconds(15));
        store.upsertAll(List.of(job));

        try (var worker = Executors.newSingleThreadExecutor()) {
            assertTrue(store.tryWithAjaxLifecycleLock(snapshot -> {
                ScanJob captured = snapshot.getFirst();
                assertEquals(job.getClaimFenceId(), captured.getClaimFenceId());
                var update = worker.submit(() -> store.updateAndGet(jobs -> {
                    jobs.getFirst().markCancelled();
                    return jobs;
                }));
                try {
                    update.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("State updates must remain available during lifecycle I/O", e);
                }

                assertEquals(ScanJobStatus.CANCELLED, store.load(job.getId()).orElseThrow().getStatus());
                assertEquals(ScanJobStatus.RUNNING, captured.getStatus());
                assertTrue(captured.isCancellationPending());
                assertEquals(now, captured.getCancelNextAttemptAt());
                assertEquals(now.plusSeconds(30), captured.getCancelDeadlineAt());
                return true;
            }).orElseThrow());
        }
    }
}
