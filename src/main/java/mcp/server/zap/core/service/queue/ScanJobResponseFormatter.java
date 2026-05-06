package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;

import java.time.Instant;
import java.util.List;

public class ScanJobResponseFormatter {

    public String formatSubmission(ScanJob job, boolean idempotentReplay, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scan job accepted")
                .append('\n')
                .append("Job ID: ").append(job.getId()).append('\n')
                .append("Type: ").append(job.getType()).append('\n')
                .append("Status: ").append(job.getStatus()).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts());

        if (job.getStatus() == ScanJobStatus.QUEUED && job.getQueuePosition() > 0) {
            sb.append('\n').append("Queue Position: ").append(job.getQueuePosition());
        }

        if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
            sb.append('\n').append("Retry Not Before: ").append(job.getNextAttemptAt());
        }

        if (hasText(job.getZapScanId())) {
            sb.append('\n').append("ZAP Scan ID: ").append(job.getZapScanId());
        }

        appendClaimDetail(sb, job, now);

        if (hasText(job.getIdempotencyKey())) {
            sb.append('\n').append("Idempotency Key: ").append(job.getIdempotencyKey());
        }

        if (idempotentReplay) {
            sb.append('\n').append("Admission: existing job returned for idempotent retry");
        }

        return sb.toString();
    }

    public String formatJobDetail(ScanJob job, int queuePosition, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scan job details")
                .append('\n')
                .append("Job ID: ").append(job.getId()).append('\n')
                .append("Type: ").append(job.getType()).append('\n')
                .append("Status: ").append(job.getStatus()).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts()).append('\n')
                .append("Progress: ").append(job.getLastKnownProgress()).append('%').append('\n')
                .append("Submitted: ").append(job.getCreatedAt());

        if (job.getStartedAt() != null) {
            sb.append('\n').append("Started: ").append(job.getStartedAt());
        }
        if (job.getCompletedAt() != null) {
            sb.append('\n').append("Completed: ").append(job.getCompletedAt());
        }
        if (queuePosition > 0) {
            sb.append('\n').append("Queue Position: ").append(queuePosition);
        }
        if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
            sb.append('\n').append("Retry Not Before: ").append(job.getNextAttemptAt());
        }
        if (hasText(job.getZapScanId())) {
            sb.append('\n').append("ZAP Scan ID: ").append(job.getZapScanId());
        }
        appendClaimDetail(sb, job, now);
        if (hasText(job.getIdempotencyKey())) {
            sb.append('\n').append("Idempotency Key: ").append(job.getIdempotencyKey());
        }
        if (hasText(job.getLastError())) {
            sb.append('\n').append("Last Error: ").append(job.getLastError());
        }
        if (isDeadLetterJob(job)) {
            sb.append('\n').append("Dead Letter: true");
        }

        return sb.toString();
    }

    public String formatJobList(List<ScanJob> snapshot, ScanJobStatus filter, Instant now) {
        List<ScanJob> jobs = snapshot == null ? List.of() : snapshot;
        StringBuilder output = new StringBuilder();
        output.append("Scan job summary")
                .append('\n')
                .append("Total jobs: ")
                .append(jobs.size())
                .append('\n')
                .append("Queue depth: ")
                .append(jobs.stream().filter(job -> job.getStatus() == ScanJobStatus.QUEUED).count())
                .append('\n')
                .append("Claimed for Dispatch: ")
                .append(jobs.stream().filter(job -> job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)).count())
                .append('\n')
                .append("Running: ")
                .append(jobs.stream().filter(job -> job.getStatus() == ScanJobStatus.RUNNING).count())
                .append('\n');

        if (filter != null) {
            output.append("Filter: ").append(filter).append('\n');
        }

        int visible = 0;
        for (ScanJob job : jobs) {
            if (filter != null && job.getStatus() != filter) {
                continue;
            }
            visible += 1;
            output.append("- ")
                    .append(job.getId())
                    .append(" | ")
                    .append(job.getType())
                    .append(" | ")
                    .append(job.getStatus())
                    .append(" | attempts=")
                    .append(job.getAttempts())
                    .append('/')
                    .append(job.getMaxAttempts())
                    .append(" | progress=")
                    .append(job.getLastKnownProgress())
                    .append('%');
            if (job.getQueuePosition() > 0) {
                output.append(" | queuePosition=").append(job.getQueuePosition());
            }
            if (isDeadLetterJob(job)) {
                output.append(" | dead-letter=true");
            }
            if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
                output.append(" | retryAt=").append(job.getNextAttemptAt());
            }
            appendClaimSummary(output, job, now);
            output.append('\n');
        }

        if (visible == 0) {
            output.append("No jobs match current filter.");
        }

        return output.toString();
    }

    public String formatDeadLetterJobs(List<ScanJob> deadLetterJobs) {
        List<ScanJob> jobs = deadLetterJobs == null ? List.of() : deadLetterJobs;
        StringBuilder output = new StringBuilder();
        output.append("Dead-letter jobs: ").append(jobs.size());
        if (jobs.isEmpty()) {
            return output.toString();
        }

        output.append('\n');
        for (ScanJob job : jobs) {
            output.append("- ")
                    .append(job.getId())
                    .append(" | ")
                    .append(job.getType())
                    .append(" | attempts=")
                    .append(job.getAttempts())
                    .append('/')
                    .append(job.getMaxAttempts());
            if (hasText(job.getLastError())) {
                output.append(" | lastError=").append(job.getLastError());
            }
            output.append('\n');
        }
        return output.toString().trim();
    }

    private void appendClaimSummary(StringBuilder output, ScanJob job, Instant now) {
        if (!hasText(job.getClaimOwnerId())) {
            return;
        }
        output.append(" | claimOwner=").append(job.getClaimOwnerId());
        if (job.getClaimExpiresAt() != null) {
            output.append(" | claimExpiresAt=").append(job.getClaimExpiresAt());
            if (job.hasLiveClaim(now)) {
                output.append(" | claimState=active");
            } else {
                output.append(" | claimState=expired");
            }
        }
    }

    private void appendClaimDetail(StringBuilder output, ScanJob job, Instant now) {
        if (!hasText(job.getClaimOwnerId())) {
            return;
        }
        output.append('\n').append("Claim Owner: ").append(job.getClaimOwnerId());
        if (job.getClaimHeartbeatAt() != null) {
            output.append('\n').append("Claim Heartbeat: ").append(job.getClaimHeartbeatAt());
        }
        if (job.getClaimExpiresAt() != null) {
            output.append('\n').append("Claim Expires: ").append(job.getClaimExpiresAt());
            output.append('\n').append("Claim State: ").append(job.hasLiveClaim(now) ? "ACTIVE" : "EXPIRED");
        }
    }

    private boolean isDeadLetterJob(ScanJob job) {
        return job.getStatus() == ScanJobStatus.FAILED && job.getAttempts() >= job.getMaxAttempts();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
