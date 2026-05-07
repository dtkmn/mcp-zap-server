package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

public record ScanJobStartResult(
        String jobId,
        ScanJobType type,
        ScanJobClaimToken claimToken,
        boolean success,
        String scanId,
        String error
) {
    public static ScanJobStartResult success(ScanJobStartTarget target, String scanId) {
        return new ScanJobStartResult(target.jobId(), target.type(), target.claimToken(), true, scanId, null);
    }

    public static ScanJobStartResult failure(ScanJobStartTarget target, String error) {
        return new ScanJobStartResult(target.jobId(), target.type(), target.claimToken(), false, null, error);
    }
}
