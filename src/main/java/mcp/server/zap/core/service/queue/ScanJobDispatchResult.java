package mcp.server.zap.core.service.queue;

import java.util.List;

public record ScanJobDispatchResult(
        List<ScanJobPollResult> pollResults,
        List<ScanJobStartResult> startResults
) {
}
