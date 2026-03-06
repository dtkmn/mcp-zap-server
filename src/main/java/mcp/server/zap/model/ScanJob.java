package mcp.server.zap.model;

import java.time.Instant;
import java.util.Map;

public class ScanJob {
    private final String id;
    private final ScanJobType type;
    private final Map<String, String> parameters;
    private final Instant createdAt;
    private final int maxAttempts;

    private ScanJobStatus status;
    private int attempts;
    private String zapScanId;
    private String lastError;
    private Instant startedAt;
    private Instant completedAt;
    private Instant nextAttemptAt;
    private int lastKnownProgress;

    public ScanJob(String id, ScanJobType type, Map<String, String> parameters, Instant createdAt, int maxAttempts) {
        this.id = id;
        this.type = type;
        this.parameters = Map.copyOf(parameters);
        this.createdAt = createdAt;
        this.maxAttempts = maxAttempts;
        this.status = ScanJobStatus.QUEUED;
        this.attempts = 0;
        this.lastKnownProgress = 0;
    }

    public String getId() {
        return id;
    }

    public ScanJobType getType() {
        return type;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public ScanJobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getZapScanId() {
        return zapScanId;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public int getLastKnownProgress() {
        return lastKnownProgress;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void incrementAttempts() {
        attempts += 1;
    }

    public void markRunning(String scanId) {
        this.status = ScanJobStatus.RUNNING;
        this.zapScanId = scanId;
        this.lastError = null;
        this.startedAt = Instant.now();
        this.completedAt = null;
        this.nextAttemptAt = null;
    }

    public void updateProgress(int progress) {
        this.lastKnownProgress = Math.max(0, Math.min(progress, 100));
    }

    public void markSucceeded(int progress) {
        this.status = ScanJobStatus.SUCCEEDED;
        this.lastKnownProgress = Math.max(100, progress);
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
    }

    public void markFailed(String errorMessage) {
        this.status = ScanJobStatus.FAILED;
        this.lastError = errorMessage;
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
    }

    public void markCancelled() {
        this.status = ScanJobStatus.CANCELLED;
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
    }

    public void markQueuedForRetry(Instant retryAt, String reason) {
        this.status = ScanJobStatus.QUEUED;
        this.zapScanId = null;
        this.lastError = reason;
        this.startedAt = null;
        this.completedAt = null;
        this.nextAttemptAt = retryAt;
        this.lastKnownProgress = 0;
    }
}
