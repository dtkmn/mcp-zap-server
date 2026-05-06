package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;

import java.util.Map;

public record ScanJobStartTarget(
        String jobId,
        ScanJobType type,
        Map<String, String> parameters,
        ScanJobClaimToken claimToken
) {
}
