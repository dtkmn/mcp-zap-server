package mcp.server.zap.core.service.jobstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresScanJobStoreRaceHarnessTest {

    private static final int RACE_ROUNDS = 8;
    private static final int CLAIM_TIMEOUT_SECONDS = 10;
    private static final Instant BASE_TIME = Instant.parse("2026-05-06T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        clearScanJobs();
    }

    @Test
    void repeatedConcurrentQueuedClaimsNeverExceedActiveCapacity() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            int currentRound = round;
            clearScanJobs();
            String firstJobId = "job-race-active-" + currentRound + "-1";
            String secondJobId = "job-race-active-" + currentRound + "-2";
            PostgresScanJobStore store = newStore();
            store.admitQueuedJob(queuedJob(firstJobId, "http://example.com/race/" + currentRound + "/1"));
            store.admitQueuedJob(queuedJob(secondJobId, "http://example.com/race/" + currentRound + "/2"));

            Instant claimAt = BASE_TIME.plusSeconds(currentRound * 60L);
            List<ScanJob> claimedJobs = runConcurrentClaims(
                    () -> newStore().claimQueuedJobs("node-a-" + currentRound, claimAt, claimAt.plusSeconds(30), 1, 1),
                    () -> newStore().claimQueuedJobs("node-b-" + currentRound, claimAt, claimAt.plusSeconds(30), 1, 1)
            );
            List<ScanJob> persistedJobs = List.of(
                    newStore().load(firstJobId).orElseThrow(),
                    newStore().load(secondJobId).orElseThrow()
            );
            List<ScanJob> liveClaims = persistedJobs.stream()
                    .filter(job -> job.hasLiveClaim(claimAt.plusSeconds(1)))
                    .toList();

            assertEquals(1, claimedJobs.size(), "round " + currentRound + " should return one claimed job");
            assertEquals(1, liveClaims.size(), "round " + currentRound + " should persist one live active claim");
            assertEquals(claimedJobs.getFirst().getId(), liveClaims.getFirst().getId());
            assertNotNull(liveClaims.getFirst().getClaimFenceId());
            assertEquals(ScanJobClaimToken.from(claimedJobs.getFirst()), ScanJobClaimToken.from(liveClaims.getFirst()));
        }
    }

    @Test
    void repeatedConcurrentExpiredRunningRecoveryNeverCreatesTwoOwners() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            int currentRound = round;
            clearScanJobs();
            String jobId = "job-race-running-" + currentRound;
            Instant expiredAt = BASE_TIME.plusSeconds(currentRound * 60L);
            ScanJob runningJob = queuedJob(jobId, "http://example.com/running/" + currentRound);
            runningJob.incrementAttempts();
            runningJob.markRunning("A-running-" + currentRound);
            runningJob.claim("node-old", expiredAt, expiredAt.plusSeconds(5));
            newStore().upsertAll(List.of(runningJob));

            Instant reclaimAt = expiredAt.plusSeconds(10);
            List<ScanJob> claimedJobs = runConcurrentClaims(
                    () -> newStore().claimRunningJobs("node-a-" + currentRound, reclaimAt, reclaimAt.plusSeconds(30)),
                    () -> newStore().claimRunningJobs("node-b-" + currentRound, reclaimAt, reclaimAt.plusSeconds(30))
            );
            ScanJob persistedJob = newStore().load(jobId).orElseThrow();

            assertEquals(1, claimedJobs.size(), "round " + currentRound + " should return one recovered running job");
            assertEquals(ScanJobStatus.RUNNING, persistedJob.getStatus());
            assertEquals("A-running-" + currentRound, persistedJob.getZapScanId());
            assertEquals(claimedJobs.getFirst().getClaimOwnerId(), persistedJob.getClaimOwnerId());
            assertEquals(ScanJobClaimToken.from(claimedJobs.getFirst()), ScanJobClaimToken.from(persistedJob));
        }
    }

    @Test
    void repeatedSameWorkerReclaimRotatesFenceAfterRenewalExpiry() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            int currentRound = round;
            clearScanJobs();
            String jobId = "job-race-reclaim-" + currentRound;
            PostgresScanJobStore store = newStore();
            store.admitQueuedJob(queuedJob(jobId, "http://example.com/reclaim/" + currentRound));

            Instant firstClaimAt = BASE_TIME.plusSeconds(currentRound * 60L);
            ScanJob firstClaim = store.claimQueuedJobs(
                    "node-a",
                    firstClaimAt,
                    firstClaimAt.plusSeconds(10),
                    1,
                    1
            ).getFirst();
            ScanJobClaimToken firstToken = ScanJobClaimToken.from(firstClaim);

            Instant renewAt = firstClaimAt.plusSeconds(5);
            assertEquals(1, store.renewClaims("node-a", List.of(jobId), renewAt, renewAt.plusSeconds(10)));
            assertEquals(firstToken, ScanJobClaimToken.from(store.load(jobId).orElseThrow()));

            Instant reclaimAt = renewAt.plusSeconds(15);
            ScanJob secondClaim = store.claimQueuedJobs(
                    "node-a",
                    reclaimAt,
                    reclaimAt.plusSeconds(30),
                    1,
                    1
            ).getFirst();
            ScanJobClaimToken secondToken = ScanJobClaimToken.from(secondClaim);

            assertNotEquals(firstToken, secondToken);
            assertTrue(store.updateClaimedJob(jobId, firstToken, reclaimAt.plusSeconds(1), job -> {
                job.markRunning("A-stale-" + currentRound);
                return job;
            }).isEmpty());
            assertTrue(store.updateClaimedJob(jobId, secondToken, reclaimAt.plusSeconds(1), job -> {
                job.markRunning("A-current-" + currentRound);
                return job;
            }).isPresent());

            ScanJob persistedJob = store.load(jobId).orElseThrow();
            assertEquals(ScanJobStatus.RUNNING, persistedJob.getStatus());
            assertEquals("A-current-" + currentRound, persistedJob.getZapScanId());
            assertEquals(secondToken, ScanJobClaimToken.from(persistedJob));
        }
    }

    private PostgresScanJobStore newStore() {
        ScanJobStoreProperties.Postgres properties = new ScanJobStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setFailFast(true);
        return new PostgresScanJobStore(properties, new ObjectMapper());
    }

    private ScanJob queuedJob(String id, String targetUrl) {
        return new ScanJob(
                id,
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", targetUrl, "recurse", "true", "policy", ""),
                BASE_TIME,
                3,
                "client-a",
                id
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
            claimedJobs.addAll(firstResult.get(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            claimedJobs.addAll(secondResult.get(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            return claimedJobs;
        } finally {
            executor.shutdownNow();
        }
    }

    private void clearScanJobs() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM scan_jobs");
        }
    }

    @FunctionalInterface
    private interface ClaimCallable {
        List<ScanJob> call() throws Exception;
    }
}
