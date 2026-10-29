package mcp.server.zap.core.model;

public enum ScanJobStatus {
    QUEUED,
    STARTING,
    START_OUTCOME_UNKNOWN,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /**
     * Return true when no more state transitions are expected.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
