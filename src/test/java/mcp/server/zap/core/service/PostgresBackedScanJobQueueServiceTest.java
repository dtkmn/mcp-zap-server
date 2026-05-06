package mcp.server.zap.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.PostgresScanJobStore;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PostgresBackedScanJobQueueServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private ActiveScanService activeScanService;
    private SpiderScanService spiderScanService;
    private AjaxSpiderService ajaxSpiderService;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() throws Exception {
        activeScanService = mock(ActiveScanService.class);
        spiderScanService = mock(SpiderScanService.class);
        ajaxSpiderService = mock(AjaxSpiderService.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(1);
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(1);

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM scan_jobs");
        }
    }

    @Test
    void admitQueuedJobAssignsQueuePositionAndReusesIdempotentRequest() {
        PostgresScanJobStore store = newStore();

        ScanJob first = new ScanJob(
                "job-1",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/1", "recurse", "true", "policy", ""),
                Instant.parse("2026-03-14T00:00:01Z"),
                3,
                "client-a",
                "idem-a"
        );
        ScanJob second = new ScanJob(
                "job-2",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/2", "recurse", "true", "policy", ""),
                Instant.parse("2026-03-14T00:00:02Z"),
                3,
                "client-a",
                "idem-b"
        );
        ScanJob duplicate = new ScanJob(
                "job-3",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/1", "recurse", "true", "policy", ""),
                Instant.parse("2026-03-14T00:00:03Z"),
                3,
                "client-a",
                "idem-a"
        );

        ScanJob admittedFirst = store.admitQueuedJob(first);
        ScanJob admittedSecond = store.admitQueuedJob(second);
        ScanJob admittedDuplicate = store.admitQueuedJob(duplicate);

        assertEquals(1, admittedFirst.getQueuePosition());
        assertEquals(2, admittedSecond.getQueuePosition());
        assertEquals("job-1", admittedDuplicate.getId());
        assertEquals(2, store.list().size());
    }

    @Test
    void submittingReplicaClaimsAndStartsSharedPostgresJobState() {
        PostgresScanJobStore leaderStore = newStore();
        PostgresScanJobStore followerStore = newStore();
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        TestQueueLeadershipCoordinator leaderCoordinator = new TestQueueLeadershipCoordinator("node-a", leadershipState);
        TestQueueLeadershipCoordinator followerCoordinator = new TestQueueLeadershipCoordinator("node-b", leadershipState);

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-live");

        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                leaderStore,
                leaderCoordinator
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                followerStore,
                followerCoordinator
        );

        String response = followerService.queueActiveScan("http://example.com/ha", "true", null, "ha-idem-1");
        String jobId = extractJobId(response);
        ScanJob queuedJob = followerStore.load(jobId).orElse(null);

        assertNotNull(queuedJob);
        assertEquals(ScanJobStatus.RUNNING, queuedJob.getStatus());
        assertEquals("A-live", queuedJob.getZapScanId());
        assertEquals("node-b", queuedJob.getClaimOwnerId());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());

        leaderService.processQueueOnceForTesting();

        ScanJob runningJob = leaderStore.load(jobId).orElse(null);
        assertNotNull(runningJob);
        assertEquals(ScanJobStatus.RUNNING, runningJob.getStatus());
        assertEquals("A-live", runningJob.getZapScanId());
        assertEquals("node-b", runningJob.getClaimOwnerId());
        verify(activeScanService, times(1)).startActiveScanJob("http://example.com/ha", "true", null);
    }

    @Test
    void concurrentReplicaAdmissionDeduplicatesIdempotencyKeyOnPostgres() throws Exception {
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-c");
        ScanJobQueueService firstService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                newStore(),
                new TestQueueLeadershipCoordinator("node-a", leadershipState)
        );
        ScanJobQueueService secondService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                newStore(),
                new TestQueueLeadershipCoordinator("node-b", leadershipState)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<String> firstAdmission = executor.submit(() -> {
                barrier.await();
                return firstService.queueActiveScan("http://example.com/concurrent", "true", null, "pg-idem-1");
            });
            Future<String> secondAdmission = executor.submit(() -> {
                barrier.await();
                return secondService.queueActiveScan("http://example.com/concurrent", "true", null, "pg-idem-1");
            });

            String firstJobId = extractJobId(firstAdmission.get());
            String secondJobId = extractJobId(secondAdmission.get());

            assertEquals(firstJobId, secondJobId);
            assertEquals(1, newStore().list().size());
            assertTrue(newStore().load(firstJobId).orElseThrow().getIdempotencyKey().equals("pg-idem-1"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRunningClaimCanBeRecoveredOnPostgresWithoutDuplicateStart() {
        SharedLeadershipState leadershipState = new SharedLeadershipState("node-a");
        ScanJobQueueService leaderService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                newStore(),
                new TestQueueLeadershipCoordinator("node-a", leadershipState)
        );
        ScanJobQueueService followerService = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
                urlValidationService,
                scanLimitProperties,
                3,
                false,
                newStore(),
                new TestQueueLeadershipCoordinator("node-b", leadershipState)
        );

        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-recover");
        when(activeScanService.getActiveScanProgressPercent("A-recover")).thenReturn(100);

        String jobId = extractJobId(leaderService.queueActiveScan("http://example.com/recover-pg", "true", null, "pg-recover"));
        ScanJob runningJob = newStore().load(jobId).orElseThrow();
        runningJob.claim("node-a", Instant.now().minusSeconds(30), Instant.now().minusSeconds(1));
        newStore().upsertAll(java.util.List.of(runningJob));

        followerService.processQueueOnceForTesting();

        ScanJob recoveredJob = newStore().load(jobId).orElseThrow();
        assertEquals(ScanJobStatus.SUCCEEDED, recoveredJob.getStatus());
        verify(activeScanService, times(1)).startActiveScanJob("http://example.com/recover-pg", "true", null);
        verify(activeScanService).getActiveScanProgressPercent("A-recover");
    }

    @Test
    void staleClaimTokenCannotUpdateReclaimedPostgresJob() {
        PostgresScanJobStore store = newStore();
        ScanJob job = queuedJob("job-pg-stale", ScanJobType.ACTIVE_SCAN, "http://example.com/stale", "pg-stale-token");
        store.admitQueuedJob(job);

        Instant firstClaimAt = Instant.parse("2026-05-06T00:00:10Z");
        ScanJob firstClaim = store.claimQueuedJobs(
                "node-a",
                firstClaimAt,
                firstClaimAt.plusSeconds(10),
                1,
                1
        ).getFirst();
        ScanJobClaimToken staleToken = ScanJobClaimToken.from(firstClaim);

        Instant secondClaimAt = Instant.parse("2026-05-06T00:00:30Z");
        ScanJob secondClaim = store.claimQueuedJobs(
                "node-a",
                secondClaimAt,
                secondClaimAt.plusSeconds(30),
                1,
                1
        ).getFirst();
        ScanJobClaimToken currentToken = ScanJobClaimToken.from(secondClaim);

        assertTrue(store.updateClaimedJob("job-pg-stale", staleToken, secondClaimAt.plusSeconds(1), claimedJob -> {
            claimedJob.markRunning("A-stale");
            return claimedJob;
        }).isEmpty());
        assertEquals(ScanJobStatus.QUEUED, store.load("job-pg-stale").orElseThrow().getStatus());

        assertTrue(store.updateClaimedJob("job-pg-stale", currentToken, secondClaimAt.plusSeconds(1), claimedJob -> {
            claimedJob.markRunning("A-current");
            return claimedJob;
        }).isPresent());
        ScanJob runningJob = store.load("job-pg-stale").orElseThrow();
        assertEquals(ScanJobStatus.RUNNING, runningJob.getStatus());
        assertEquals("A-current", runningJob.getZapScanId());
    }

    @Test
    void concurrentQueuedClaimsRespectActiveCapacityOnPostgres() throws Exception {
        PostgresScanJobStore store = newStore();
        store.admitQueuedJob(queuedJob(
                "job-pg-active-race-1",
                ScanJobType.ACTIVE_SCAN,
                "http://example.com/race-1",
                "pg-active-race-1"
        ));
        store.admitQueuedJob(queuedJob(
                "job-pg-active-race-2",
                ScanJobType.ACTIVE_SCAN,
                "http://example.com/race-2",
                "pg-active-race-2"
        ));

        Instant claimAt = Instant.parse("2026-05-06T00:02:00Z");
        List<ScanJob> claimedJobs = runConcurrentClaims(
                () -> newStore().claimQueuedJobs("node-a", claimAt, claimAt.plusSeconds(30), 1, 1),
                () -> newStore().claimQueuedJobs("node-b", claimAt, claimAt.plusSeconds(30), 1, 1)
        );

        List<ScanJob> liveActiveClaims = newStore().list().stream()
                .filter(job -> job.getType().isActiveFamily())
                .filter(job -> job.getStatus() == ScanJobStatus.QUEUED)
                .filter(job -> job.hasLiveClaim(claimAt.plusSeconds(1)))
                .toList();

        assertEquals(1, claimedJobs.size());
        assertEquals(1, liveActiveClaims.size());
        assertEquals(claimedJobs.getFirst().getId(), liveActiveClaims.getFirst().getId());
        assertNotNull(liveActiveClaims.getFirst().getClaimFenceId());
    }

    @Test
    void concurrentExpiredRunningRecoveryClaimsSingleOwnerOnPostgres() throws Exception {
        PostgresScanJobStore store = newStore();
        ScanJob runningJob = queuedJob(
                "job-pg-running-race",
                ScanJobType.ACTIVE_SCAN,
                "http://example.com/running-race",
                "pg-running-race"
        );
        runningJob.incrementAttempts();
        runningJob.markRunning("A-running-race");
        runningJob.claim(
                "node-old",
                Instant.parse("2026-05-06T00:01:00Z"),
                Instant.parse("2026-05-06T00:01:10Z")
        );
        store.upsertAll(List.of(runningJob));

        Instant reclaimAt = Instant.parse("2026-05-06T00:02:00Z");
        List<ScanJob> claimedJobs = runConcurrentClaims(
                () -> newStore().claimRunningJobs("node-a", reclaimAt, reclaimAt.plusSeconds(30)),
                () -> newStore().claimRunningJobs("node-b", reclaimAt, reclaimAt.plusSeconds(30))
        );

        ScanJob persistedJob = newStore().load("job-pg-running-race").orElseThrow();
        assertEquals(1, claimedJobs.size());
        assertEquals(ScanJobStatus.RUNNING, persistedJob.getStatus());
        assertEquals("A-running-race", persistedJob.getZapScanId());
        assertEquals(claimedJobs.getFirst().getClaimOwnerId(), persistedJob.getClaimOwnerId());
        assertEquals(ScanJobClaimToken.from(claimedJobs.getFirst()), ScanJobClaimToken.from(persistedJob));
    }

    @Test
    void postgresRenewalPreservesFenceAndKeepsInFlightTokenUsable() {
        PostgresScanJobStore store = newStore();
        store.admitQueuedJob(queuedJob(
                "job-pg-renew-fence",
                ScanJobType.ACTIVE_SCAN,
                "http://example.com/renew-fence",
                "pg-renew-fence"
        ));

        Instant claimAt = Instant.parse("2026-05-06T00:03:00Z");
        ScanJob claimedJob = store.claimQueuedJobs(
                "node-a",
                claimAt,
                claimAt.plusSeconds(30),
                1,
                1
        ).getFirst();
        ScanJobClaimToken inFlightToken = ScanJobClaimToken.from(claimedJob);

        Instant renewAt = claimAt.plusSeconds(5);
        int renewedCount = store.renewClaims("node-a", List.of("job-pg-renew-fence"), renewAt, renewAt.plusSeconds(45));

        ScanJob renewedJob = store.load("job-pg-renew-fence").orElseThrow();
        assertEquals(1, renewedCount);
        assertEquals(inFlightToken, ScanJobClaimToken.from(renewedJob));
        assertEquals(renewAt, renewedJob.getClaimHeartbeatAt());
        assertEquals(renewAt.plusSeconds(45), renewedJob.getClaimExpiresAt());

        assertTrue(store.updateClaimedJob(
                "job-pg-renew-fence",
                inFlightToken,
                renewAt.plusSeconds(1),
                job -> {
                    job.markRunning("A-renewed-token");
                    return job;
                }
        ).isPresent());
        assertEquals("A-renewed-token", store.load("job-pg-renew-fence").orElseThrow().getZapScanId());
    }

    @Test
    void expiredPostgresClaimCannotRenewOrApplyResultWithoutReclaim() {
        PostgresScanJobStore store = newStore();
        store.admitQueuedJob(queuedJob(
                "job-pg-expired-update",
                ScanJobType.ACTIVE_SCAN,
                "http://example.com/expired-update",
                "pg-expired-update"
        ));

        Instant claimAt = Instant.parse("2026-05-06T00:04:00Z");
        ScanJob claimedJob = store.claimQueuedJobs(
                "node-a",
                claimAt,
                claimAt.plusSeconds(10),
                1,
                1
        ).getFirst();
        ScanJobClaimToken expiredToken = ScanJobClaimToken.from(claimedJob);

        Instant afterExpiry = claimAt.plusSeconds(15);
        int renewedCount = store.renewClaims(
                "node-a",
                List.of("job-pg-expired-update"),
                afterExpiry,
                afterExpiry.plusSeconds(30)
        );
        assertEquals(0, renewedCount);
        assertTrue(store.updateClaimedJob(
                "job-pg-expired-update",
                expiredToken,
                afterExpiry,
                job -> {
                    job.markRunning("A-expired-should-not-apply");
                    return job;
                }
        ).isEmpty());

        ScanJob unchangedJob = store.load("job-pg-expired-update").orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals(claimAt, unchangedJob.getClaimHeartbeatAt());
        assertEquals(claimAt.plusSeconds(10), unchangedJob.getClaimExpiresAt());
    }

    private PostgresScanJobStore newStore() {
        mcp.server.zap.core.configuration.ScanJobStoreProperties.Postgres properties =
                new mcp.server.zap.core.configuration.ScanJobStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setFailFast(true);
        return new PostgresScanJobStore(properties, new ObjectMapper());
    }

    private ScanJob queuedJob(String id, ScanJobType type, String targetUrl, String idempotencyKey) {
        return new ScanJob(
                id,
                type,
                Map.of("targetUrl", targetUrl, "recurse", "true", "policy", ""),
                Instant.parse("2026-05-06T00:00:00Z"),
                3,
                "client-a",
                idempotencyKey
        );
    }

    private List<ScanJob> runConcurrentClaims(ClaimCallable firstClaim, ClaimCallable secondClaim) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<List<ScanJob>> firstResult = executor.submit(() -> {
                barrier.await();
                return firstClaim.call();
            });
            Future<List<ScanJob>> secondResult = executor.submit(() -> {
                barrier.await();
                return secondClaim.call();
            });

            ArrayList<ScanJob> claimedJobs = new ArrayList<>();
            claimedJobs.addAll(firstResult.get());
            claimedJobs.addAll(secondResult.get());
            return claimedJobs;
        } finally {
            executor.shutdownNow();
        }
    }

    private String extractJobId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Job ID: ")) {
                return line.substring("Job ID: ".length()).trim();
            }
        }
        throw new AssertionError("Unable to extract job ID from response: " + response);
    }

    @FunctionalInterface
    private interface ClaimCallable {
        List<ScanJob> call() throws Exception;
    }

    private static final class SharedLeadershipState {
        private final AtomicReference<String> leaderId;

        private SharedLeadershipState(String initialLeaderId) {
            this.leaderId = new AtomicReference<>(initialLeaderId);
        }

        private String getLeaderId() {
            return leaderId.get();
        }
    }

    private static final class TestQueueLeadershipCoordinator implements QueueLeadershipCoordinator {
        private final String nodeId;
        private final SharedLeadershipState state;
        private volatile boolean wasLeader;

        private TestQueueLeadershipCoordinator(String nodeId, SharedLeadershipState state) {
            this.nodeId = nodeId;
            this.state = state;
        }

        @Override
        public LeadershipDecision evaluateLeadership() {
            boolean isLeaderNow = nodeId.equals(state.getLeaderId());
            boolean acquired = !wasLeader && isLeaderNow;
            boolean lost = wasLeader && !isLeaderNow;
            wasLeader = isLeaderNow;
            return new LeadershipDecision(isLeaderNow, acquired, lost);
        }

        @Override
        public String nodeId() {
            return nodeId;
        }
    }
}
