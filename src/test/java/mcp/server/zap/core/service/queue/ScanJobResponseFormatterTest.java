package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanJobResponseFormatterTest {
    private final ScanJobResponseFormatter formatter = new ScanJobResponseFormatter();

    @Test
    void identifiesCleanupJobsAndTheirSourceInDetailsAndListing() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob cleanup = new ScanJob("cleanup-1", ScanJobType.ACTIVE_SCAN,
                Map.of(ScanJob.CLEANUP_OF_JOB_ID, "source-1"), now, 1);
        cleanup.markRunning("123");
        cleanup.requestCancellation(now, now.plusSeconds(30));

        assertThat(formatter.formatJobDetail(cleanup, 0, now))
                .contains("Cleanup for Job ID: source-1", "ZAP Scan ID: 123", "cancellation pending");
        assertThat(formatter.formatJobList(List.of(cleanup), null, now))
                .contains("cleanupForJob=source-1", "cleanup-1 | ACTIVE_SCAN | RUNNING (cancellation pending)");
    }

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
    void ajaxTerminalJobsDoNotExposeQueueLifecycleValuesAsCrawlPercentages() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob succeededJob = new ScanJob("ajax-ended", ScanJobType.AJAX_SPIDER, Map.of(), now, 3);
        succeededJob.markRunning("ajax-spider:1");
        succeededJob.markSucceeded(100);
        ScanJob cancelledJob = new ScanJob("ajax-cancelled", ScanJobType.AJAX_SPIDER, Map.of(), now, 3);
        cancelledJob.markRunning("ajax-spider:2");
        cancelledJob.updateProgress(100);
        cancelledJob.markCancelled();

        for (ScanJob job : List.of(succeededJob, cancelledJob)) {
            assertThat(formatter.formatJobDetail(job, 0, now))
                    .contains("Progress: unavailable (ZAP does not report a percentage)")
                    .doesNotContain("100%");
        }
        assertThat(formatter.formatJobDetail(cancelledJob, 0, now))
                .contains("Status: CANCELLED")
                .doesNotContain("SUCCEEDED");
        assertThat(formatter.formatJobDetail(succeededJob, 0, now))
                .contains("Status: SUCCEEDED (ZAP reports stopped; crawl outcome unknown)");
        assertThat(formatter.formatSubmission(succeededJob, true, now))
                .contains("Status: SUCCEEDED (ZAP reports stopped; crawl outcome unknown)");
        assertThat(formatter.formatJobList(List.of(succeededJob, cancelledJob), null, now))
                .contains("ajax-ended | AJAX_SPIDER | SUCCEEDED (ZAP reports stopped; crawl outcome unknown)")
                .contains("ajax-cancelled | AJAX_SPIDER | CANCELLED")
                .contains("progress=unavailable (ZAP does not report a percentage)")
                .doesNotContain("100%");
    }

    @Test
    void explainsEngineWaitingWithoutPresentingItAsAStartupFailure() {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob job = new ScanJob("job-busy", ScanJobType.AJAX_SPIDER, Map.of(), now.minusSeconds(30), 2);
        job.markWaitingForEngine(now, now.plusSeconds(10), "Engine is finishing another scan");

        for (String output : List.of(formatter.formatSubmission(job, false, now),
                formatter.formatJobDetail(job, 1, now))) {
            assertThat(output)
                    .contains("Status: QUEUED (waiting for engine)")
                    .contains("Attempts: 0/2")
                    .contains("Waiting Since: 2026-05-06T00:00:00Z")
                    .contains("Waiting Reason: Engine is finishing another scan")
                    .contains("Retry Not Before: 2026-05-06T00:00:10Z")
                    .doesNotContain("Last Error:", "Dead Letter:");
        }
        assertThat(formatter.formatJobList(List.of(job), null, now))
                .contains("QUEUED (waiting for engine)", "attempts=0/2")
                .contains("waitingSince=2026-05-06T00:00:00Z")
                .contains("reason=Engine is finishing another scan")
                .contains("retryAt=2026-05-06T00:00:10Z");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void showsCancellationStateAndDeadlineWithoutClaimingSuccess(boolean pending) {
        Instant now = Instant.parse("2026-05-06T00:00:00Z");
        ScanJob job = new ScanJob("ajax-cancelling", ScanJobType.AJAX_SPIDER, Map.of(), now.minusSeconds(5), 2);
        job.markRunning("ajax-spider:1");
        job.requestCancellation(now, now.plusSeconds(30));
        String reason = pending ? "ZAP stop request failed" : "Unable to confirm cancellation; scan may still be running";
        job.scheduleCancellationRetry(pending ? now.plusSeconds(1) : null, reason);
        String status = pending ? "RUNNING (cancellation pending)"
                : "RUNNING (cancellation unconfirmed; automatic stop retries ended)";

        for (String output : List.of(formatter.formatSubmission(job, true, now),
                formatter.formatJobDetail(job, 0, now))) {
            assertThat(output).contains("Status: " + status,
                    "Cancellation Requested: 2026-05-06T00:00:00Z",
                    "Cancellation Retry Deadline: 2026-05-06T00:00:30Z")
                    .doesNotContain("Status: CANCELLED", "Status: SUCCEEDED");
            if (pending) {
                assertThat(output).contains("Next Stop Attempt: 2026-05-06T00:00:01Z");
            } else {
                assertThat(output).doesNotContain("Next Stop Attempt:");
            }
        }
        assertThat(formatter.formatJobDetail(job, 0, now)).contains("Last Error: " + reason);
        String listing = formatter.formatJobList(List.of(job), null, now);
        assertThat(listing).contains("ajax-cancelling | AJAX_SPIDER | " + status,
                "cancellationDeadline=2026-05-06T00:00:30Z", "reason=" + reason);
        if (pending) {
            assertThat(listing).contains("nextStopAttempt=2026-05-06T00:00:01Z");
        } else {
            assertThat(listing).doesNotContain("nextStopAttempt=");
        }
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
