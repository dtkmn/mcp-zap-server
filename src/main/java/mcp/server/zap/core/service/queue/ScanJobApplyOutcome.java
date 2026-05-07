package mcp.server.zap.core.service.queue;

import java.util.List;

public record ScanJobApplyOutcome(List<ScanJobStopRequest> stopRequests, RuntimeException persistenceFailure) {

    public ScanJobApplyOutcome(List<ScanJobStopRequest> stopRequests) {
        this(stopRequests, null);
    }
}
