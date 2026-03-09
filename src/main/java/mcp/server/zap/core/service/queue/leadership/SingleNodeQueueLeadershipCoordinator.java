package mcp.server.zap.core.service.queue.leadership;

import java.util.concurrent.atomic.AtomicBoolean;

public class SingleNodeQueueLeadershipCoordinator implements QueueLeadershipCoordinator {

    private final AtomicBoolean announced = new AtomicBoolean(false);

    /**
     * Always reports leader in single-node mode, emitting acquire event once.
     */
    @Override
    public LeadershipDecision evaluateLeadership() {
        if (announced.compareAndSet(false, true)) {
            return new LeadershipDecision(true, true, false);
        }
        return LeadershipDecision.asLeader();
    }
}
