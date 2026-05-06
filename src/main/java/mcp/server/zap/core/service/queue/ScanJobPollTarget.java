package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

public record ScanJobPollTarget(String jobId, ScanJobType type, String scanId, ScanJobClaimToken claimToken) {
}
