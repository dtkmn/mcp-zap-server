package mcp.server.zap.core.model;

public enum ScanJobStatus {
    QUEUED,
    STARTING,
    START_OUTCOME_UNKNOWN,
    RUNNING,
    TRACKING_LOST,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /**
     * Return true when no more state transitions are expected.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isRunningOrHeld() {
        return this == RUNNING || this == STARTING || this == START_OUTCOME_UNKNOWN || this == TRACKING_LOST;
    }
}
