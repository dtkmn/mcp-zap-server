package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanJobQueueStateNormalizerTest {

    private final ScanJobQueueStateNormalizer normalizer = new ScanJobQueueStateNormalizer();

    @Test
    void normalizesQueuedOrderAndClearsNonQueuedPositions() {
        ScanJob laterQueuedJob = queuedJob("job-later", "2026-05-06T00:00:02Z");
        laterQueuedJob.assignQueuePosition(10);
        ScanJob earlierQueuedJob = queuedJob("job-earlier", "2026-05-06T00:00:01Z");
        earlierQueuedJob.assignQueuePosition(2);
        ScanJob runningJob = queuedJob("job-running", "2026-05-06T00:00:03Z");
        runningJob.markRunning("active-1");
        runningJob.assignQueuePosition(7);

        ScanJobQueueState state = normalizer.normalize(List.of(laterQueuedJob, runningJob, earlierQueuedJob));

        assertTrue(state.normalized());
        assertEquals(List.of("job-earlier", "job-later"), List.copyOf(state.queuedJobIds()));
        assertEquals(1, state.jobs().get("job-earlier").getQueuePosition());
        assertEquals(2, state.jobs().get("job-later").getQueuePosition());
        assertEquals(0, state.jobs().get("job-running").getQueuePosition());
    }

    @Test
    void repairsRunningJobsWithoutZapScanId() {
        ScanJob invalidRunningJob = queuedJob("job-running-no-id", "2026-05-06T00:00:01Z");
        invalidRunningJob.incrementAttempts();
        invalidRunningJob.markRunning(null);

        ScanJobQueueState state = normalizer.normalize(List.of(invalidRunningJob));
        ScanJob repairedJob = state.jobs().get("job-running-no-id");

        assertTrue(state.normalized());
        assertEquals(1, state.repairedRunningJobs());
        assertEquals(ScanJobStatus.FAILED, repairedJob.getStatus());
        assertTrue(repairedJob.getLastError().contains("Missing ZAP scan ID"));
    }

    @Test
    void storedJobsOfAssignsPositionsFromQueueState() {
        ScanJob firstQueuedJob = queuedJob("job-first", "2026-05-06T00:00:01Z");
        ScanJob secondQueuedJob = queuedJob("job-second", "2026-05-06T00:00:02Z");
        ScanJob runningJob = queuedJob("job-running", "2026-05-06T00:00:03Z");
        runningJob.markRunning("active-1");

        Map<String, ScanJob> jobs = new LinkedHashMap<>();
        jobs.put(firstQueuedJob.getId(), firstQueuedJob);
        jobs.put(secondQueuedJob.getId(), secondQueuedJob);
        jobs.put(runningJob.getId(), runningJob);

        ArrayDeque<String> queuedIds = new ArrayDeque<>();
        queuedIds.add("job-second");
        queuedIds.add("missing-job");
        queuedIds.add("job-running");
        queuedIds.add("job-first");

        List<ScanJob> storedJobs = normalizer.storedJobsOf(jobs, queuedIds);

        assertEquals(List.of("job-first", "job-second", "job-running"), storedJobs.stream().map(ScanJob::getId).toList());
        assertEquals(1, secondQueuedJob.getQueuePosition());
        assertEquals(2, firstQueuedJob.getQueuePosition());
        assertEquals(0, runningJob.getQueuePosition());
    }

    private ScanJob queuedJob(String id, String createdAt) {
        return new ScanJob(
                id,
                ScanJobType.ACTIVE_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "http://example.com/" + id),
                Instant.parse(createdAt),
                3
        );
    }
}
