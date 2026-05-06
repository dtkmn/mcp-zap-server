package mcp.server.zap.core.service.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanJobClaimManagerTest {

    @Test
    void claimsQueuedWorkAndSuppressesDuplicateStartTargetsWhileInFlight() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = new ScanJob(
                "job-queued",
                ScanJobType.ACTIVE_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                3
        );
        store.admitQueuedJob(job);

        Instant now = Instant.parse("2026-05-06T00:00:10Z");
        ScanJobClaimManager claimManager = new ScanJobClaimManager(store, "node-a", ScanJobClaimMetrics.noop());

        ScanJobWorkPlan firstPlan = claimManager.claimWork(List.of(), 1, 1, now, now.plusSeconds(30));
        ScanJobWorkPlan duplicatePlan = claimManager.claimWork(store.list(), 1, 1, now.plusSeconds(1), now.plusSeconds(31));

        assertEquals(List.of(), firstPlan.pollTargets());
        assertEquals(1, firstPlan.startTargets().size());
        assertEquals("job-queued", firstPlan.startTargets().getFirst().jobId());
        assertEquals(ScanJobType.ACTIVE_SCAN, firstPlan.startTargets().getFirst().type());
        assertNotNull(firstPlan.startTargets().getFirst().claimToken());
        assertEquals("node-a", store.load("job-queued").orElseThrow().getClaimOwnerId());
        assertEquals(List.of(), duplicatePlan.startTargets());
        assertEquals(List.of(), duplicatePlan.pollTargets());
    }

    @Test
    void renewsOnlyJobsOwnedByCurrentWorkerAndMarkedInFlight() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = new ScanJob(
                "job-renew",
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                2
        );
        store.admitQueuedJob(job);

        Instant now = Instant.parse("2026-05-06T00:00:10Z");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScanJobClaimManager claimManager = new ScanJobClaimManager(
                store,
                "node-a",
                ScanJobClaimMetrics.create(meterRegistry)
        );
        claimManager.claimWork(List.of(), 1, 1, now, now.plusSeconds(30));
        ScanJobClaimToken dispatchedToken = ScanJobClaimToken.from(store.load("job-renew").orElseThrow());

        claimManager.renewInFlightClaims(now.plusSeconds(5), now.plusSeconds(45));

        ScanJob renewedJob = store.load("job-renew").orElseThrow();
        assertEquals("node-a", renewedJob.getClaimOwnerId());
        assertEquals(dispatchedToken, ScanJobClaimToken.from(renewedJob));
        assertEquals(now.plusSeconds(5), renewedJob.getClaimHeartbeatAt());
        assertEquals(now.plusSeconds(45), renewedJob.getClaimExpiresAt());
        assertEquals(1.0, renewedClaimCount(meterRegistry));
    }

    @Test
    void recordsOnlyConfirmedRenewedClaims() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = new ScanJob(
                "job-expired-renewal",
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                2
        );
        store.admitQueuedJob(job);

        Instant now = Instant.parse("2026-05-06T00:00:10Z");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ScanJobClaimManager claimManager = new ScanJobClaimManager(
                store,
                "node-a",
                ScanJobClaimMetrics.create(meterRegistry)
        );
        claimManager.claimWork(List.of(), 1, 1, now, now.plusSeconds(10));
        store.load("job-expired-renewal")
                .orElseThrow()
                .claim("node-a", now.minusSeconds(30), now.minusSeconds(1));

        claimManager.renewInFlightClaims(now.plusSeconds(5), now.plusSeconds(45));

        ScanJob expiredJob = store.load("job-expired-renewal").orElseThrow();
        assertEquals(now.minusSeconds(30), expiredJob.getClaimHeartbeatAt());
        assertEquals(0.0, renewedClaimCount(meterRegistry));
    }

    @Test
    void expiredInFlightClaimsAreDroppedBeforeRedispatch() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        ScanJob job = new ScanJob(
                "job-expired",
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                2
        );
        store.admitQueuedJob(job);

        Instant firstClaimAt = Instant.parse("2026-05-06T00:00:10Z");
        ScanJobClaimManager claimManager = new ScanJobClaimManager(store, "node-a", ScanJobClaimMetrics.noop());
        ScanJobWorkPlan firstPlan = claimManager.claimWork(List.of(), 1, 1, firstClaimAt, firstClaimAt.plusSeconds(10));
        ScanJobClaimToken firstToken = firstPlan.startTargets().getFirst().claimToken();

        Instant redispatchAt = Instant.parse("2026-05-06T00:00:30Z");
        claimManager.retainValidInFlightClaims(
                Map.of("job-expired", store.load("job-expired").orElseThrow()),
                redispatchAt
        );
        ScanJobWorkPlan redispatchPlan = claimManager.claimWork(
                store.list(),
                1,
                1,
                redispatchAt,
                redispatchAt.plusSeconds(30)
        );

        assertEquals(1, redispatchPlan.startTargets().size());
        assertEquals("job-expired", redispatchPlan.startTargets().getFirst().jobId());
        assertNotNull(redispatchPlan.startTargets().getFirst().claimToken().fenceId());
        assertEquals(redispatchAt, store.load("job-expired").orElseThrow().getClaimHeartbeatAt());
        assertNotEquals(firstToken, redispatchPlan.startTargets().getFirst().claimToken());
    }

    @Test
    void recoversExpiredRunningClaimAsPollTargetWithoutStartingDuplicateWork() {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        Instant createdAt = Instant.parse("2026-05-06T00:00:00Z");
        Instant now = Instant.parse("2026-05-06T00:01:00Z");
        ScanJob runningJob = new ScanJob(
                "job-running",
                ScanJobType.ACTIVE_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"),
                createdAt,
                3
        );
        runningJob.incrementAttempts();
        runningJob.markRunning("active-1");
        runningJob.claim("node-a", now.minusSeconds(60), now.minusSeconds(1));
        store.upsertAll(List.of(runningJob));

        ScanJobClaimManager claimManager = new ScanJobClaimManager(store, "node-b", ScanJobClaimMetrics.noop());
        ScanJobWorkPlan plan = claimManager.claimWork(store.list(), 1, 1, now, now.plusSeconds(30));

        assertEquals(1, plan.pollTargets().size());
        assertEquals("job-running", plan.pollTargets().getFirst().jobId());
        assertEquals("active-1", plan.pollTargets().getFirst().scanId());
        assertNotNull(plan.pollTargets().getFirst().claimToken());
        assertEquals(List.of(), plan.startTargets());
        ScanJob recoveredJob = store.load("job-running").orElseThrow();
        assertEquals("node-b", recoveredJob.getClaimOwnerId());
        assertTrue(recoveredJob.hasLiveClaim(now));
    }

    private double renewedClaimCount(SimpleMeterRegistry meterRegistry) {
        return meterRegistry.counter("mcp.zap.queue.claim.events", "event", "renewed").count();
    }
}
