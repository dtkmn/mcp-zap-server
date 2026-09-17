package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

public record ScanJobStopRequest(ScanJobType type, String scanId, String jobId) {
    public ScanJobStopRequest(ScanJobType type, String scanId) {
        this(type, scanId, null);
    }
}
