package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

public record ScanJobStartResult(
        String jobId,
        ScanJobType type,
        ScanJobClaimToken claimToken,
        boolean success,
        String scanId,
        String error,
        boolean engineBusy
) {
    public ScanJobStartResult(String jobId, ScanJobType type, ScanJobClaimToken claimToken,
                              boolean success, String scanId, String error) {
        this(jobId, type, claimToken, success, scanId, error, false);
    }

    public static ScanJobStartResult success(ScanJobStartTarget target, String scanId) {
        return new ScanJobStartResult(target.jobId(), target.type(), target.claimToken(), true, scanId, null);
    }

    public static ScanJobStartResult failure(ScanJobStartTarget target, String error) {
        return new ScanJobStartResult(target.jobId(), target.type(), target.claimToken(), false, null, error);
    }

    public static ScanJobStartResult busy(ScanJobStartTarget target, String reason) {
        return new ScanJobStartResult(target.jobId(), target.type(), target.claimToken(), false, null, reason, true);
    }
}
