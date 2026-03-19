package mcp.server.zap.core.model;

import java.time.Instant;
import java.util.Map;

/**
 * Mutable queue job state used by scan orchestration and persistence.
 */
public class ScanJob {
    private final String id;
    private final ScanJobType type;
    private final Map<String, String> parameters;
    private final Instant createdAt;
    private final int maxAttempts;
    private final String requesterId;
    private final String idempotencyKey;

    private ScanJobStatus status;
    private int attempts;
    private String zapScanId;
    private String lastError;
    private Instant startedAt;
    private Instant completedAt;
    private Instant nextAttemptAt;
    private int lastKnownProgress;
    private int queuePosition;
    private String claimOwnerId;
    private Instant claimHeartbeatAt;
    private Instant claimExpiresAt;

    /**
     * Create a new queued job with zero attempts.
     */
    public ScanJob(String id, ScanJobType type, Map<String, String> parameters, Instant createdAt, int maxAttempts) {
        this(id, type, parameters, createdAt, maxAttempts, null, null);
    }

    /**
     * Create a new queued job with optional requester-scoped idempotency metadata.
     */
    public ScanJob(String id,
                   ScanJobType type,
                   Map<String, String> parameters,
                   Instant createdAt,
                   int maxAttempts,
                   String requesterId,
                   String idempotencyKey) {
        this(
                id,
                type,
                parameters,
                createdAt,
                maxAttempts,
                requesterId,
                idempotencyKey,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null
        );
    }

    private ScanJob(String id,
                    ScanJobType type,
                    Map<String, String> parameters,
                    Instant createdAt,
                    int maxAttempts,
                    String requesterId,
                    String idempotencyKey,
                    ScanJobStatus status,
                    int attempts,
                    String zapScanId,
                    String lastError,
                    Instant startedAt,
                    Instant completedAt,
                    Instant nextAttemptAt,
                    int lastKnownProgress,
                    int queuePosition,
                    String claimOwnerId,
                    Instant claimHeartbeatAt,
                    Instant claimExpiresAt) {
        this.id = id;
        this.type = type;
        this.parameters = Map.copyOf(parameters);
        this.createdAt = createdAt;
        this.maxAttempts = maxAttempts;
        this.requesterId = normalizeRequesterId(requesterId);
        this.idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        this.status = status;
        this.attempts = attempts;
        this.zapScanId = zapScanId;
        this.lastError = lastError;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.lastKnownProgress = lastKnownProgress;
        this.queuePosition = Math.max(queuePosition, 0);
        this.claimOwnerId = normalizeClaimOwnerId(claimOwnerId);
        this.claimHeartbeatAt = claimHeartbeatAt;
        this.claimExpiresAt = claimExpiresAt;
    }

    /**
     * Restore a job from persisted snapshot state.
     */
    public static ScanJob restore(String id,
                                  ScanJobType type,
                                  Map<String, String> parameters,
                                  Instant createdAt,
                                  int maxAttempts,
                                  String requesterId,
                                  String idempotencyKey,
                                  ScanJobStatus status,
                                  int attempts,
                                  String zapScanId,
                                  String lastError,
                                  Instant startedAt,
                                  Instant completedAt,
                                  Instant nextAttemptAt,
                                  int lastKnownProgress,
                                  int queuePosition,
                                  String claimOwnerId,
                                  Instant claimHeartbeatAt,
                                  Instant claimExpiresAt) {
        return new ScanJob(
                id,
                type,
                parameters,
                createdAt,
                maxAttempts,
                requesterId,
                idempotencyKey,
                status,
                attempts,
                zapScanId,
                lastError,
                startedAt,
                completedAt,
                nextAttemptAt,
                lastKnownProgress,
                queuePosition,
                claimOwnerId,
                claimHeartbeatAt,
                claimExpiresAt
        );
    }

    /**
     * Restore a job from persisted state without idempotency metadata.
     */
    public static ScanJob restore(String id,
                                  ScanJobType type,
                                  Map<String, String> parameters,
                                  Instant createdAt,
                                  int maxAttempts,
                                  ScanJobStatus status,
                                  int attempts,
                                  String zapScanId,
                                  String lastError,
                                  Instant startedAt,
                                  Instant completedAt,
                                  Instant nextAttemptAt,
                                  int lastKnownProgress,
                                  int queuePosition) {
        return restore(
                id,
                type,
                parameters,
                createdAt,
                maxAttempts,
                null,
                null,
                status,
                attempts,
                zapScanId,
                lastError,
                startedAt,
                completedAt,
                nextAttemptAt,
                lastKnownProgress,
                queuePosition,
                null,
                null,
                null
        );
    }

    /**
     * Return the immutable job identifier.
     */
    public String getId() {
        return id;
    }

    /**
     * Return the scan job type.
     */
    public ScanJobType getType() {
        return type;
    }

    /**
     * Return immutable scan parameters.
     */
    public Map<String, String> getParameters() {
        return parameters;
    }

    /**
     * Return creation timestamp.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Return maximum allowed attempts for this job.
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Return the authenticated requester/client identity, when known.
     */
    public String getRequesterId() {
        return requesterId;
    }

    /**
     * Return optional requester-scoped idempotency key.
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Return current lifecycle status.
     */
    public ScanJobStatus getStatus() {
        return status;
    }

    /**
     * Return attempts already consumed.
     */
    public int getAttempts() {
        return attempts;
    }

    /**
     * Return linked ZAP scan ID when running.
     */
    public String getZapScanId() {
        return zapScanId;
    }

    /**
     * Return last known error message.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Return timestamp when execution started.
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Return timestamp when job finished.
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Return latest observed progress percentage.
     */
    public int getLastKnownProgress() {
        return lastKnownProgress;
    }

    /**
     * Return 1-based queue position when queued, or 0 otherwise.
     */
    public int getQueuePosition() {
        return queuePosition;
    }

    /**
     * Return retry-not-before timestamp for queued retries.
     */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * Return claim owner node ID when a replica currently owns this job.
     */
    public String getClaimOwnerId() {
        return claimOwnerId;
    }

    /**
     * Return the last claim heartbeat timestamp.
     */
    public Instant getClaimHeartbeatAt() {
        return claimHeartbeatAt;
    }

    /**
     * Return the claim expiry timestamp.
     */
    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    /**
     * Increment attempt counter before dispatching work.
     */
    public void incrementAttempts() {
        attempts += 1;
    }

    /**
     * Transition to RUNNING with the assigned scan ID.
     */
    public void markRunning(String scanId) {
        this.status = ScanJobStatus.RUNNING;
        this.zapScanId = scanId;
        this.lastError = null;
        this.startedAt = Instant.now();
        this.completedAt = null;
        this.nextAttemptAt = null;
        this.queuePosition = 0;
    }

    /**
     * Update progress while preserving bounds [0,100].
     */
    public void updateProgress(int progress) {
        this.lastKnownProgress = Math.max(0, Math.min(progress, 100));
    }

    /**
     * Transition to SUCCEEDED and mark completion metadata.
     */
    public void markSucceeded(int progress) {
        this.status = ScanJobStatus.SUCCEEDED;
        this.lastKnownProgress = Math.max(100, progress);
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
        this.queuePosition = 0;
        clearClaim();
    }

    /**
     * Transition to FAILED and persist failure details.
     */
    public void markFailed(String errorMessage) {
        this.status = ScanJobStatus.FAILED;
        this.lastError = errorMessage;
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
        this.queuePosition = 0;
        clearClaim();
    }

    /**
     * Transition to CANCELLED and mark completion metadata.
     */
    public void markCancelled() {
        this.status = ScanJobStatus.CANCELLED;
        this.completedAt = Instant.now();
        this.nextAttemptAt = null;
        this.queuePosition = 0;
        clearClaim();
    }

    /**
     * Reset runtime state and queue job for a future retry window.
     */
    public void markQueuedForRetry(Instant retryAt, String reason) {
        this.status = ScanJobStatus.QUEUED;
        this.zapScanId = null;
        this.lastError = reason;
        this.startedAt = null;
        this.completedAt = null;
        this.nextAttemptAt = retryAt;
        this.lastKnownProgress = 0;
        this.queuePosition = 0;
        clearClaim();
    }

    /**
     * Assign current 1-based queue position for durable read paths.
     */
    public void assignQueuePosition(int queuePosition) {
        this.queuePosition = Math.max(queuePosition, 0);
    }

    /**
     * Claim ownership of this job for a replica until the supplied expiry time.
     */
    public void claim(String claimOwnerId, Instant heartbeatAt, Instant claimExpiresAt) {
        this.claimOwnerId = normalizeClaimOwnerId(claimOwnerId);
        this.claimHeartbeatAt = heartbeatAt;
        this.claimExpiresAt = claimExpiresAt;
    }

    /**
     * Return true when this job is currently claimed by the given replica.
     */
    public boolean isClaimedBy(String workerId) {
        String normalizedWorkerId = normalizeClaimOwnerId(workerId);
        return normalizedWorkerId != null && normalizedWorkerId.equals(claimOwnerId);
    }

    /**
     * Return true when a replica claim exists and has not yet expired.
     */
    public boolean hasLiveClaim(Instant now) {
        return claimOwnerId != null
                && claimExpiresAt != null
                && now != null
                && claimExpiresAt.isAfter(now);
    }

    /**
     * Return true when claim metadata exists but the lease has already expired.
     */
    public boolean claimExpiredAt(Instant now) {
        return claimOwnerId != null
                && claimExpiresAt != null
                && now != null
                && !claimExpiresAt.isAfter(now);
    }

    /**
     * Remove any current claim metadata.
     */
    public void clearClaim() {
        this.claimOwnerId = null;
        this.claimHeartbeatAt = null;
        this.claimExpiresAt = null;
    }

    private String normalizeRequesterId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "anonymous";
        }
        return value.trim();
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeClaimOwnerId(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
