package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;

import java.time.Instant;

public class ScanJobResponseFormatter {

    public String formatSubmission(ScanJob job, boolean idempotentReplay, Instant now) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scan job accepted")
                .append('\n')
                .append("Job ID: ").append(job.getId()).append('\n')
                .append("Type: ").append(job.getType()).append('\n')
                .append("Status: ").append(formatStatus(job)).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts());

        if (job.getStatus() == ScanJobStatus.QUEUED && job.getQueuePosition() > 0) {
            sb.append('\n').append("Queue Position: ").append(job.getQueuePosition());
        }

        if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
            sb.append('\n').append("Retry Not Before: ").append(job.getNextAttemptAt());
        }

        appendEngineWaitDetail(sb, job);
        appendCancellationDetail(sb, job);

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
                .append("Status: ").append(formatStatus(job)).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts()).append('\n')
                .append("Progress: ").append(formatProgress(job)).append('\n')
                .append("Submitted: ").append(job.getCreatedAt());

        if (job.isCleanupJob()) {
            sb.append('\n').append("Cleanup for Job ID: ").append(job.getCleanupOfJobId());
        }

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
            if (!isWaitingForEngine(job)) {
                sb.append('\n').append("Last Error: ").append(job.getLastError());
            }
        }
        appendEngineWaitDetail(sb, job);
        appendCancellationDetail(sb, job);
        if (isDeadLetterJob(job)) {
            sb.append('\n').append("Dead Letter: true");
        }

        return sb.toString();
    }

    private String formatStatus(ScanJob job) {
        if (job.isCancellationRequested()) {
            return job.getStatus().name() + (job.isCancellationPending()
                    ? " (cancellation pending)" : " (cancellation unconfirmed; automatic stop retries ended)");
        }
        if (isWaitingForEngine(job)) {
            return "QUEUED (waiting for engine)";
        }
        if ((job.getType() == ScanJobType.AJAX_SPIDER || job.getType() == ScanJobType.CLIENT_SPIDER)
                && job.getStatus() == ScanJobStatus.SUCCEEDED) {
            return "SUCCEEDED (ZAP reports stopped; crawl outcome unknown)";
        }
        return job.getStatus().name();
    }

    private String formatProgress(ScanJob job) {
        if (job.hasUnresolvedLaunch()) return "unknown (possible execution remains held)";
        return job.getType() == ScanJobType.AJAX_SPIDER
                ? "unavailable (ZAP does not report a percentage)"
                : job.getLastKnownProgress() + "%";
    }

    private boolean isWaitingForEngine(ScanJob job) {
        return job.getStatus() == ScanJobStatus.QUEUED && job.getBusyWaitStartedAt() != null
                && !job.isCancellationRequested();
    }

    private void appendCancellationDetail(StringBuilder output, ScanJob job) {
        if (job.isCancellationRequested()) {
            output.append('\n').append("Cancellation Requested: ").append(job.getCancelRequestedAt())
                    .append('\n').append("Cancellation Retry Deadline: ").append(job.getCancelDeadlineAt());
            if (job.getCancelNextAttemptAt() != null) {
                output.append('\n').append("Next Stop Attempt: ").append(job.getCancelNextAttemptAt());
            }
        }
    }

    private void appendEngineWaitDetail(StringBuilder output, ScanJob job) {
        if (isWaitingForEngine(job)) {
            output.append('\n').append("Waiting Since: ").append(job.getBusyWaitStartedAt())
                    .append('\n').append("Waiting Reason: ").append(job.getLastError());
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
