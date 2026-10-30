package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

public record ScanJobStopRequest(ScanJobType type, String scanId, String jobId,
                                 String launchAttemptId, long resolutionVersion) {
    public ScanJobStopRequest(ScanJobType type, String scanId, String jobId) {
        this(type, scanId, jobId, null, -1);
    }
    public ScanJobStopRequest(ScanJobType type, String scanId) {
        this(type, scanId, null);
    }

    public static ScanJobStopRequest owned(mcp.server.zap.core.model.ScanJob job) {
        if (job.getLaunchAttempt() == null) throw new IllegalStateException("Native stop requires its launch receipt");
        return new ScanJobStopRequest(job.getType(), job.getZapScanId(), job.getId(),
                job.getLaunchAttempt().id(), job.getLaunchAttempt().resolutionVersion());
    }
}
