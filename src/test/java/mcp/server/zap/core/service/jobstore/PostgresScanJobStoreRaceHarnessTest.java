package mcp.server.zap.core.service.jobstore;

import tools.jackson.databind.ObjectMapper;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import mcp.server.zap.core.service.postgres.PostgresSchemaReadinessValidator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("docker")
@Testcontainers
class PostgresScanJobStoreRaceHarnessTest {

    private static final int RACE_ROUNDS = 8;
    private static final int CLAIM_TIMEOUT_SECONDS = 10;
    private static final Instant BASE_TIME = Instant.parse("2026-05-06T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
    void readinessRequiresCancellationRetryMigration() throws Exception {
        String schema = "before_cancellation_retry";
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        var migration = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema);
        try {
            migration.target("7").load().migrate();
            ScanJobStoreProperties properties = new ScanJobStoreProperties();
            properties.setBackend("postgres");
            properties.getPostgres().setUrl(url);
            properties.getPostgres().setUsername(POSTGRES.getUsername());
            properties.getPostgres().setPassword(POSTGRES.getPassword());
            PostgresSchemaReadinessValidator validator = new PostgresSchemaReadinessValidator(
                    properties, new ScanHistoryLedgerProperties(), new TokenRevocationStoreProperties()
            );

            IllegalStateException error = assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
            assertTrue(error.getMessage().contains("Apply Flyway migrations"));
            assertTrue(error.getMessage().contains("busy_wait_started_at"));
            assertTrue(error.getMessage().contains("busy_wait_count"));
            assertTrue(error.getMessage().contains("cancel_requested_at"));
            assertTrue(error.getMessage().contains("cancel_deadline_at"));
            assertTrue(error.getMessage().contains("cancel_next_attempt_at"));
            assertTrue(error.getMessage().contains("cancel_attempt_count"));

            migration.target("latest").load().migrate();
            assertDoesNotThrow(validator::afterPropertiesSet);
        } finally {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void engineBusyDeferralsSurviveReloadAndClaimByAnotherWorker() {
        ScanJob job = queuedJob("job-engine-busy", "http://example.com/waiting");
        Instant firstWaitAt = BASE_TIME.plusSeconds(1);
        Instant firstRetryAt = firstWaitAt.plusSeconds(10);
        job.markWaitingForEngine(firstWaitAt, firstRetryAt, "Engine is busy");
        newStore().admitQueuedJob(job);

        ScanJob restored = newStore().load(job.getId()).orElseThrow();
        assertEquals(ScanJobStatus.QUEUED, restored.getStatus());
        assertEquals(firstWaitAt, restored.getBusyWaitStartedAt());
        assertEquals(1, restored.getBusyWaitCount());
        assertEquals(0, restored.getAttempts());
        assertEquals(firstRetryAt, restored.getNextAttemptAt());
        assertEquals("Engine is busy", restored.getLastError());
        assertTrue(newStore().claimQueuedJobs(
                "node-a", firstRetryAt.minusSeconds(1), firstRetryAt.plusSeconds(30), 1, 1
        ).isEmpty());

        ScanJob claimed = newStore().claimQueuedJobs(
                "node-a", firstRetryAt, firstRetryAt.plusSeconds(30), 1, 1
        ).getFirst();
        Instant nextRetryAt = firstRetryAt.plusSeconds(20);
        newStore().updateClaimedJob(job.getId(), ScanJobClaimToken.from(claimed), firstRetryAt, current -> {
            current.markWaitingForEngine(firstRetryAt, nextRetryAt, "Engine is still busy");
            return current;
        }).orElseThrow();

        ScanJob reclaimed = newStore().claimQueuedJobs(
                "node-b", nextRetryAt, nextRetryAt.plusSeconds(30), 1, 1
        ).getFirst();
        assertEquals("node-b", reclaimed.getClaimOwnerId());
        assertEquals(ScanJobStatus.QUEUED, reclaimed.getStatus());
        assertEquals(firstWaitAt, reclaimed.getBusyWaitStartedAt());
        assertEquals(2, reclaimed.getBusyWaitCount());
        assertEquals(0, reclaimed.getAttempts());
        assertEquals(nextRetryAt, reclaimed.getNextAttemptAt());
        assertEquals("Engine is still busy", reclaimed.getLastError());
    }

    @Test
    void cancellationStateSurvivesReloadAndKeepsAjaxSlotUntilConfirmed() {
        ScanJob job = new ScanJob("job-cancelling", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com/cancelling"), BASE_TIME, 2);
        Instant requestedAt = BASE_TIME.plusSeconds(1);
        Instant deadline = requestedAt.plusSeconds(30);
        Instant retryAt = requestedAt.plusSeconds(10);
        job.claim("node-old", BASE_TIME, BASE_TIME.plusSeconds(5));
        job.requestCancellation(requestedAt, deadline);
        job.scheduleCancellationRetry(retryAt, "Stop not yet accepted");
        job.requestCancellation(requestedAt.plusSeconds(2), deadline.plusSeconds(30));
        newStore().admitQueuedJob(job);
        ScanJob nextJob = new ScanJob("job-next-ajax", ScanJobType.AJAX_SPIDER,
                Map.of("targetUrl", "http://example.com/next"), BASE_TIME.plusSeconds(2), 2);
        newStore().admitQueuedJob(nextJob);

        ScanJob restored = newStore().load(job.getId()).orElseThrow();
        assertTrue(restored.isCancellationPending());
        assertEquals(requestedAt, restored.getCancelRequestedAt());
        assertEquals(deadline, restored.getCancelDeadlineAt());
        assertEquals(retryAt, restored.getCancelNextAttemptAt());
        assertEquals(1, restored.getCancelAttemptCount());
        assertEquals("Stop not yet accepted", restored.getLastError());
        assertEquals(0, restored.getAttempts());
        assertTrue(newStore().claimQueuedJobs("node-b", retryAt, retryAt.plusSeconds(30), 1, 3).isEmpty(),
                "Expired launch claims with cancellation intent must not be restarted or release the AJAX slot");

        restored.markRunning("AJAX");
        newStore().upsertAll(List.of(restored));
        ScanJob claimed = newStore().claimRunningJobs("node-b", retryAt, retryAt.plusSeconds(30)).getFirst();
        assertTrue(claimed.isCancellationPending(), "A late start must retain the cancellation request");
        newStore().updateClaimedJob(job.getId(), ScanJobClaimToken.from(claimed), retryAt, current -> {
            current.scheduleCancellationRetry(retryAt.plusSeconds(10), "Still starting");
            return current;
        }).orElseThrow();
        ScanJob pending = newStore().load(job.getId()).orElseThrow();
        assertEquals(2, pending.getCancelAttemptCount());
        assertEquals(requestedAt, pending.getCancelRequestedAt());
        assertEquals(deadline, pending.getCancelDeadlineAt());

        pending.recordCancellationFailure("Unable to confirm cancellation");
        newStore().upsertAll(List.of(pending));
        ScanJob unconfirmed = newStore().load(job.getId()).orElseThrow();
        assertTrue(unconfirmed.isCancellationRequested());
        assertFalse(unconfirmed.isCancellationPending());
        assertEquals(2, unconfirmed.getCancelAttemptCount());
        assertNull(unconfirmed.getCancelNextAttemptAt());
        assertTrue(newStore().claimQueuedJobs("node-c", deadline, deadline.plusSeconds(30), 1, 3).isEmpty());

        unconfirmed.markCancelled();
        newStore().upsertAll(List.of(unconfirmed));
        ScanJob cancelled = newStore().load(job.getId()).orElseThrow();
        assertFalse(cancelled.isCancellationRequested());
        assertNull(cancelled.getLastError());
        assertNull(cancelled.getCancelNextAttemptAt());
        assertEquals(List.of(nextJob.getId()), newStore()
                .claimQueuedJobs("node-c", deadline, deadline.plusSeconds(30), 1, 3)
                .stream().map(ScanJob::getId).toList());
    }

    @Test
    void ajaxLifecycleLockExcludesOtherWorkersWithoutBlockingQueueStateUpdates() {
        PostgresScanJobStore firstStore = newStore();
        PostgresScanJobStore secondStore = newStore();
        firstStore.admitQueuedJob(queuedJob("job-before-lock", "http://example.com/before"));
        AtomicInteger competingActions = new AtomicInteger();

        assertEquals("first", firstStore.tryWithAjaxLifecycleLock(snapshot -> {
            assertEquals(List.of("job-before-lock"), snapshot.stream().map(ScanJob::getId).toList());
            assertTrue(secondStore.tryWithAjaxLifecycleLock(otherSnapshot -> {
                competingActions.incrementAndGet();
                return "competing";
            }).isEmpty());
            secondStore.admitQueuedJob(queuedJob("job-during-lock", "http://example.com/during"));
            return "first";
        }).orElseThrow());
        assertEquals(0, competingActions.get());
        assertEquals(2, secondStore.tryWithAjaxLifecycleLock(List::size).orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = ScanJobType.class, names = {"ACTIVE_SCAN", "SPIDER_SCAN", "CLIENT_SPIDER"})
    void cleanupBlocksSourceRetryAcrossWorkersWhileOtherJobsUseRemainingCapacity(ScanJobType type) {
        ScanJob source = new ScanJob("source", type, Map.of(), BASE_TIME, 3);
        ScanJob cleanup = new ScanJob("cleanup", type,
                Map.of(ScanJob.CLEANUP_OF_JOB_ID, source.getId()), BASE_TIME, 1);
        cleanup.markRunning("late-scan");
        cleanup.requestCancellation(BASE_TIME, BASE_TIME.plusSeconds(30));
        cleanup.deferCancellationAttempt(BASE_TIME.plusSeconds(5));
        cleanup.recordCancellationFailure("Cancellation unconfirmed");
        ScanJob queuedCleanup = new ScanJob("queued-cleanup", type,
                Map.of(ScanJob.CLEANUP_OF_JOB_ID, "another-source"), BASE_TIME, 1);
        ScanJob unrelated = new ScanJob("unrelated", type, Map.of(), BASE_TIME.plusSeconds(1), 3);
        ScanJob overflow = new ScanJob("overflow", type, Map.of(), BASE_TIME.plusSeconds(2), 3);
        ScanJob otherFamily = new ScanJob("other-family",
                type.isActiveFamily() ? ScanJobType.SPIDER_SCAN : ScanJobType.ACTIVE_SCAN,
                Map.of(), BASE_TIME, 3);
        newStore().upsertAll(List.of(source, cleanup, queuedCleanup, unrelated, overflow, otherFamily));

        ScanJob restored = newStore().load(cleanup.getId()).orElseThrow();
        assertTrue(restored.isCleanupJob());
        assertEquals(source.getId(), restored.getCleanupOfJobId());
        assertTrue(restored.isCancellationRequested());
        assertFalse(restored.isCancellationPending());
        assertEquals(0, restored.getCancelAttemptCount());

        Set<String> claimed = newStore()
                .claimQueuedJobs("worker-1", BASE_TIME, BASE_TIME.plusSeconds(60), 2, 2)
                .stream().map(ScanJob::getId).collect(Collectors.toSet());

        assertEquals(Set.of(unrelated.getId(), otherFamily.getId()), claimed);
        assertEquals(ScanJobStatus.QUEUED, newStore().load(source.getId()).orElseThrow().getStatus());
        assertEquals(ScanJobStatus.QUEUED, newStore().load(queuedCleanup.getId()).orElseThrow().getStatus());
        newStore().updateAndGet(jobs -> {
            jobs.stream().filter(job -> cleanup.getId().equals(job.getId()))
                    .findFirst().orElseThrow().markCancelled();
            return jobs;
        });

        assertEquals(List.of(source.getId()), newStore()
                .claimQueuedJobs("worker-2", BASE_TIME.plusSeconds(1), BASE_TIME.plusSeconds(60), 2, 2)
                .stream().map(ScanJob::getId).toList());
    }

    @Test
    void ajaxLifecycleActionIsNotRetriedAndLockIsReleasedAfterFailure() {
        AtomicInteger actionCalls = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("Action failed",
                new SQLException("Must not retry an engine side effect", "40001"));
        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> newStore()
                .tryWithAjaxLifecycleLock(snapshot -> {
                    actionCalls.incrementAndGet();
                    throw expected;
                }));

        assertSame(expected, actual);
        assertEquals(1, actionCalls.get());
        assertEquals("recovered", newStore().tryWithAjaxLifecycleLock(snapshot -> "recovered").orElseThrow());
    }

    @Test
    void ajaxLifecycleSnapshotFailureNeverCallsActionEvenWithFailSoftStore() {
        ScanJobStoreProperties.Postgres properties = new ScanJobStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setTableName("missing_ajax_scan_jobs");
        properties.setFailFast(false);
        PostgresScanJobStore store = new PostgresScanJobStore(properties, new ObjectMapper());
        AtomicInteger actionCalls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> store.tryWithAjaxLifecycleLock(snapshot -> {
            actionCalls.incrementAndGet();
            return "unsafe";
        }));
        assertEquals(0, actionCalls.get());
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

    @Test
    void concurrentCleanupInsertionPreservesTheFirstCompletedCleanupAndDeadline() throws Exception {
        ScanJob source = queuedJob("source-job", "http://example.com/late-start");
        newStore().upsertAll(List.of(source));
        CountDownLatch firstUpdateEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstUpdate = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<ScanJob>> firstUpdate = workers.submit(() -> newStore().updateAndGet(jobs -> {
                ScanJob cleanup = new ScanJob("cleanup-job", ScanJobType.ACTIVE_SCAN,
                        Map.of(ScanJob.CLEANUP_OF_JOB_ID, source.getId()), BASE_TIME, 0);
                cleanup.markRunning("123");
                cleanup.requestCancellation(BASE_TIME, BASE_TIME.plusSeconds(30));
                cleanup.markCancelled();
                jobs.add(cleanup);
                firstUpdateEntered.countDown();
                try {
                    assertTrue(releaseFirstUpdate.await(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return jobs;
            }));
            assertTrue(firstUpdateEntered.await(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<List<ScanJob>> duplicateUpdate = workers.submit(() -> newStore().updateAndGet(jobs -> {
                if (jobs.stream().noneMatch(job -> "cleanup-job".equals(job.getId()))) {
                    ScanJob cleanup = new ScanJob("cleanup-job", ScanJobType.ACTIVE_SCAN,
                            Map.of(ScanJob.CLEANUP_OF_JOB_ID, source.getId()), BASE_TIME.plusSeconds(1), 0);
                    cleanup.markRunning("123");
                    cleanup.requestCancellation(BASE_TIME.plusSeconds(1), BASE_TIME.plusSeconds(31));
                    jobs.add(cleanup);
                }
                return jobs;
            }));

            // Confirm the second transaction is waiting in PostgreSQL, rather than relying
            // on thread timing to ensure its old snapshot would miss the concurrent insert.
            assertTrue(awaitScanJobLockWait());
            releaseFirstUpdate.countDown();
            firstUpdate.get(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            duplicateUpdate.get(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            ScanJob persisted = newStore().load("cleanup-job").orElseThrow();
            assertEquals(ScanJobStatus.CANCELLED, persisted.getStatus());
            assertEquals(BASE_TIME, persisted.getCancelRequestedAt());
            assertEquals(BASE_TIME.plusSeconds(30), persisted.getCancelDeadlineAt());
            assertFalse(persisted.isCancellationPending());
        } finally {
            releaseFirstUpdate.countDown();
            workers.shutdownNow();
        }
    }

    private boolean awaitScanJobLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLAIM_TIMEOUT_SECONDS);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%scan_jobs%')")) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return true;
                    }
                }
                Thread.sleep(10);
            }
        }
        return false;
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
