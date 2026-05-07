package mcp.server.zap.core.service.queue;

import java.util.List;

public record ScanJobWorkPlan(List<ScanJobPollTarget> pollTargets, List<ScanJobStartTarget> startTargets) {
}
