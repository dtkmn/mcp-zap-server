package mcp.server.zap.core.service.jobstore;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryScanJobStoreTest {

    @ParameterizedTest
    @EnumSource(value = ScanJobType.class, names = {"ACTIVE_SCAN", "SPIDER_SCAN"})
    void cleanupBlocksSourceRetryAndUsesCapacityWithoutBlockingOtherScanFamilies(ScanJobType type) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob source = new ScanJob("source", type, Map.of(), now, 3);
        ScanJob cleanup = new ScanJob("cleanup", type,
                Map.of(ScanJob.CLEANUP_OF_JOB_ID, source.getId()), now, 1);
        cleanup.markRunning("late-scan");
        cleanup.requestCancellation(now, now.plusSeconds(30));
        cleanup.recordCancellationFailure("Cancellation unconfirmed");
        ScanJob queuedCleanup = new ScanJob("queued-cleanup", type,
                Map.of(ScanJob.CLEANUP_OF_JOB_ID, "another-source"), now, 1);
        ScanJob unrelated = new ScanJob("unrelated", type, Map.of(), now.plusSeconds(1), 3);
        ScanJob overflow = new ScanJob("overflow", type, Map.of(), now.plusSeconds(2), 3);
        ScanJob otherFamily = new ScanJob("other-family",
                type.isActiveFamily() ? ScanJobType.SPIDER_SCAN : ScanJobType.ACTIVE_SCAN,
                Map.of(), now, 3);
        store.upsertAll(List.of(source, cleanup, queuedCleanup, unrelated, overflow, otherFamily));

        Set<String> claimed = store.claimQueuedJobs("worker-1", now, now.plusSeconds(60), 2, 2)
                .stream().map(ScanJob::getId).collect(Collectors.toSet());

        assertEquals(Set.of(unrelated.getId(), otherFamily.getId()), claimed);
        assertEquals(ScanJobStatus.QUEUED, source.getStatus());
        assertEquals(ScanJobStatus.QUEUED, queuedCleanup.getStatus());
        assertTrue(cleanup.isCleanupJob());
        assertEquals(source.getId(), cleanup.getCleanupOfJobId());

        store.updateAndGet(jobs -> {
            jobs.stream().filter(job -> cleanup.getId().equals(job.getId()))
                    .findFirst().orElseThrow().markCancelled();
            return jobs;
        });

        assertEquals(List.of(source.getId()), store
                .claimQueuedJobs("worker-2", now.plusSeconds(1), now.plusSeconds(60), 2, 2)
                .stream().map(ScanJob::getId).toList());
    }

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
