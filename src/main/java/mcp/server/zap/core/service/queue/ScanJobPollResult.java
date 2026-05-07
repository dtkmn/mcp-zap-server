package mcp.server.zap.core.service.queue;

public record ScanJobPollResult(
        String jobId,
        ScanJobClaimToken claimToken,
        boolean success,
        int progress,
        String error
) {
    public static ScanJobPollResult success(ScanJobPollTarget target, int progress) {
        return new ScanJobPollResult(target.jobId(), target.claimToken(), true, progress, null);
    }

    public static ScanJobPollResult failure(ScanJobPollTarget target, String error) {
        return new ScanJobPollResult(target.jobId(), target.claimToken(), false, 0, error);
    }
}
