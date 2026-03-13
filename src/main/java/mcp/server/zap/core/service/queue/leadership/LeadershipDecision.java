package mcp.server.zap.core.service.queue.leadership;

public record LeadershipDecision(
        boolean leader,
        boolean acquiredLeadership,
        boolean lostLeadership
) {

    /**
     * Return steady-state leader decision without transition flags.
     */
    public static LeadershipDecision asLeader() {
        return new LeadershipDecision(true, false, false);
    }

    /**
     * Return steady-state follower decision without transition flags.
     */
    public static LeadershipDecision asFollower() {
        return new LeadershipDecision(false, false, false);
    }
}
