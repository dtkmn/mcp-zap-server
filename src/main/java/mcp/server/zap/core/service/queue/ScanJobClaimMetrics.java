package mcp.server.zap.core.service.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public final class ScanJobClaimMetrics {
    private final Counter queuedClaimCounter;
    private final Counter runningRecoveryCounter;
    private final Counter expiredClaimRecoveryCounter;
    private final Counter renewedClaimCounter;
    private final Counter claimConflictCounter;
    private final Counter lateResultCleanupCounter;

    private ScanJobClaimMetrics(
            Counter queuedClaimCounter,
            Counter runningRecoveryCounter,
            Counter expiredClaimRecoveryCounter,
            Counter renewedClaimCounter,
            Counter claimConflictCounter,
            Counter lateResultCleanupCounter
    ) {
        this.queuedClaimCounter = queuedClaimCounter;
        this.runningRecoveryCounter = runningRecoveryCounter;
        this.expiredClaimRecoveryCounter = expiredClaimRecoveryCounter;
        this.renewedClaimCounter = renewedClaimCounter;
        this.claimConflictCounter = claimConflictCounter;
        this.lateResultCleanupCounter = lateResultCleanupCounter;
    }

    public static ScanJobClaimMetrics create(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return noop();
        }

        return new ScanJobClaimMetrics(
                counter(meterRegistry, "queued_claimed"),
                counter(meterRegistry, "running_recovered"),
                counter(meterRegistry, "expired_claim_recovered"),
                counter(meterRegistry, "renewed"),
                counter(meterRegistry, "conflict"),
                counter(meterRegistry, "late_result_cleanup")
        );
    }

    public static ScanJobClaimMetrics noop() {
        return new ScanJobClaimMetrics(null, null, null, null, null, null);
    }

    public void recordQueuedClaims(int count) {
        increment(queuedClaimCounter, count);
    }

    public void recordRunningRecoveries(int count) {
        increment(runningRecoveryCounter, count);
    }

    public void recordExpiredClaimRecoveries(int count) {
        increment(expiredClaimRecoveryCounter, count);
    }

    public void recordRenewedClaims(int count) {
        increment(renewedClaimCounter, count);
    }

    public void recordClaimConflicts(int count) {
        increment(claimConflictCounter, count);
    }

    public void recordLateResultCleanups(int count) {
        increment(lateResultCleanupCounter, count);
    }

    private static Counter counter(MeterRegistry meterRegistry, String event) {
        return Counter.builder("mcp.zap.queue.claim.events")
                .tag("event", event)
                .register(meterRegistry);
    }

    private static void increment(Counter counter, int count) {
        if (counter != null && count > 0) {
            counter.increment(count);
        }
    }
}
