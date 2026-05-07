package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;

import java.util.Deque;
import java.util.Map;

public record ScanJobQueueState(
        Map<String, ScanJob> jobs,
        Deque<String> queuedJobIds,
        boolean normalized,
        int repairedRunningJobs
) {
}
