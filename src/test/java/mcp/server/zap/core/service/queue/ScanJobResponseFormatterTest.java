package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanJobResponseFormatterTest {
    private final ScanJobResponseFormatter formatter = new ScanJobResponseFormatter();

    @Test
    void formatsQueuedSubmissionWithoutQueueServiceState() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob job = new ScanJob(
                "job-1",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "https://example.com"),
                now.minusSeconds(30),
                3,
                "client-a",
                "idem-1"
        );
        job.assignQueuePosition(2);
        job.claim("worker-a", now.minusSeconds(1), now.plusSeconds(30));

        String output = formatter.formatSubmission(job, true, now);

        assertEquals("""
                Scan job accepted
                Job ID: job-1
                Type: ACTIVE_SCAN
                Status: QUEUED
                Attempts: 0/3
                Queue Position: 2
                Claim Owner: worker-a
                Claim Heartbeat: 2026-05-05T23:59:59Z
                Claim Expires: 2026-05-06T00:00:30Z
                Claim State: ACTIVE
                Idempotency Key: idem-1
                Admission: existing job returned for idempotent retry""", output);
    }

    @Test
    void formatsJobDetailWithFullDeadLetterContract() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob job = ScanJob.restore(
                "job-dead",
                ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", "https://example.com"),
                now.minusSeconds(60),
                2,
                ScanJobStatus.FAILED,
                2,
                null,
                "startup failed",
                null,
                now,
                null,
                0,
                0
        );

        String detail = formatter.formatJobDetail(job, job.getQueuePosition(), now);

        assertEquals("""
                Scan job details
                Job ID: job-dead
                Type: SPIDER_SCAN
                Status: FAILED
                Attempts: 2/2
                Progress: 0%
                Submitted: 2026-05-05T23:59:00Z
                Completed: 2026-05-06T00:00:00Z
                Last Error: startup failed
                Dead Letter: true""", detail);
    }

    @Test
    void formatsFilteredJobListWithFullContract() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob queuedJob = new ScanJob(
                "job-queued",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "https://example.com/queued"),
                now.minusSeconds(30),
                3
        );
        queuedJob.assignQueuePosition(1);
        queuedJob.claim("worker-a", now.minusSeconds(1), now.plusSeconds(30));

        ScanJob runningJob = new ScanJob(
                "job-running",
                ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", "https://example.com/running"),
                now.minusSeconds(20),
                2
        );
        runningJob.incrementAttempts();
        runningJob.markRunning("spider-1");
        runningJob.updateProgress(50);

        String output = formatter.formatJobList(List.of(queuedJob, runningJob), ScanJobStatus.QUEUED, now);

        assertEquals("""
                Scan job summary
                Total jobs: 2
                Queue depth: 1
                Claimed for Dispatch: 1
                Running: 1
                Filter: QUEUED
                - job-queued | ACTIVE_SCAN | QUEUED | attempts=0/3 | progress=0% | queuePosition=1 | claimOwner=worker-a | claimExpiresAt=2026-05-06T00:00:30Z | claimState=active
                """, output);
    }

    @Test
    void formatsDeadLetterListWithFullContract() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob job = ScanJob.restore(
                "job-dead",
                ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", "https://example.com"),
                now.minusSeconds(60),
                2,
                ScanJobStatus.FAILED,
                2,
                null,
                "startup failed",
                null,
                now,
                null,
                0,
                0
        );

        String output = formatter.formatDeadLetterJobs(List.of(job));

        assertEquals("""
                Dead-letter jobs: 1
                - job-dead | SPIDER_SCAN | attempts=2/2 | lastError=startup failed""", output);
    }
}
