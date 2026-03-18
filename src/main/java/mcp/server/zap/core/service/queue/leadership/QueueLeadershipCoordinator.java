package mcp.server.zap.core.service.queue.leadership;

public interface QueueLeadershipCoordinator extends AutoCloseable {

    LeadershipDecision evaluateLeadership();

    default String nodeId() {
        return "single-node";
    }

    @Override
    default void close() {
        // no-op by default
    }
}
