package mcp.server.zap.core.service.queue.leadership;

public interface QueueLeadershipCoordinator extends AutoCloseable {

    LeadershipDecision evaluateLeadership();

    @Override
    default void close() {
        // no-op by default
    }
}
