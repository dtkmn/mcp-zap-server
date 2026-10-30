package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.PostgresScanJobStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@Tag("docker")
@Testcontainers
class PostgresScanLaunchSafetyTest {
    private static final long FIXTURE_MEMORY_BYTES = 256L * 1024 * 1024;
    private static final int WAIT_SECONDS = 10;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withReuse(false)
            .withCommand("postgres", "-c", "shared_buffers=32MB", "-c", "max_connections=30")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(FIXTURE_MEMORY_BYTES)
                    .withNanoCPUs(1_000_000_000L));

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clearJobs() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM scan_jobs");
        }
    }

    @Test
    void acceptedStartWithLostReplyIsNotRelaunchedAcrossCyclesAndControllerRestart() throws Exception {
        AtomicInteger nativeStarts = new AtomicInteger();
        PostgresScanJobStore originalStore = newStore();
        String attemptId;
        try (ScanJobLaunchSafety original = safety(originalStore, "worker-a")) {
            ScanJobStartTarget target = claim(originalStore, job("uncertain", ScanJobType.ACTIVE_SCAN), "worker-a");
            try (ScanJobDispatcher dispatcher = dispatcher(original, launch -> {
                launch.beforeDispatch();
                nativeStarts.incrementAndGet();
                throw new IllegalStateException("Fake engine accepted the scan but lost its reply");
            })) {
                ScanJobDispatchResult dispatched = dispatcher.dispatch(new ScanJobWorkPlan(List.of(), List.of(target)));
                ScanJobApplyOutcome applied = applier(originalStore, "worker-a").applyResults(
                        dispatched.pollResults(), dispatched.startResults(), now().plusSeconds(60));
                assertNull(applied.persistenceFailure());
                assertTrue(applied.stopRequests().isEmpty());
            }
            ScanJob held = originalStore.load("uncertain").orElseThrow();
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, held.getStatus());
            assertEquals(1, held.getAttempts());
            assertNull(held.getZapScanId());
            attemptId = held.getLaunchAttempt().id();
            originalStore.admitQueuedJob(job("waiting", ScanJobType.ACTIVE_SCAN));
            assertNoActiveAdmission(originalStore, "other-worker");
        }

        PostgresScanJobStore reopenedStore = newStore();
        try (ScanJobLaunchSafety replacement = safety(reopenedStore, "replacement-worker")) {
            ScanJob restored = reopenedStore.load("uncertain").orElseThrow();
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, restored.getStatus());
            assertEquals(attemptId, restored.getLaunchAttempt().id());
            assertEquals(0, restored.getLaunchAttempt().resolutionVersion());
            assertEquals(1, restored.getAttempts());
            assertNoActiveAdmission(reopenedStore, "replacement-worker");
            assertTrue(reopenedStore.claimRunningJobs("replacement-worker", now(), now().plusSeconds(60)).isEmpty());
        }
        assertEquals(1, nativeStarts.get());
    }

    @Test
    void joiningReplicaPreservesLiveSiblingButHoldsItsExpiredReceipt() throws Exception {
        PostgresScanJobStore firstStore = newStore();
        PostgresScanJobStore siblingStore = newStore();
        try (ScanJobLaunchSafety first = safety(firstStore, "worker-a")) {
            ScanJobLaunchSafety.StartAttempt start = first.newAttempt(
                    claim(firstStore, job("running", ScanJobType.ACTIVE_SCAN), "worker-a"));
            start.beforeDispatch();
            start.accepted("native-42");
            start.finished();
            ScanJob accepted = firstStore.load("running").orElseThrow();

            try (ScanJobLaunchSafety sibling = safety(siblingStore, "worker-b")) {
                ScanJob stillRunning = siblingStore.load("running").orElseThrow();
                assertEquals(ScanJobStatus.RUNNING, stillRunning.getStatus());
                assertEquals("native-42", stillRunning.getZapScanId());
                assertEquals(accepted.getLaunchAttempt(), stillRunning.getLaunchAttempt());
                assertEquals("worker-a", stillRunning.getClaimOwnerId());

                expireClaim(firstStore, accepted);
                sibling.refreshHolds();
                ScanJob orphan = siblingStore.load("running").orElseThrow();
                assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, orphan.getStatus());
                assertEquals("native-42", orphan.getZapScanId());
                assertEquals(accepted.getLaunchAttempt().id(), orphan.getLaunchAttempt().id());
                assertTrue(siblingStore.claimRunningJobs("worker-b", now(), now().plusSeconds(60)).isEmpty());
                siblingStore.admitQueuedJob(job("waiting", ScanJobType.ACTIVE_SCAN));
                assertNoActiveAdmission(siblingStore, "worker-b");
            }
        }
    }

    @Test
    void concurrentOperatorsCannotAttachSameNativeIdAcrossActiveScanAliases() throws Exception {
        PostgresScanJobStore firstStore = spy(newStore());
        PostgresScanJobStore secondStore = newStore();
        firstStore.upsertAll(List.of(heldJob("active", ScanJobType.ACTIVE_SCAN),
                heldJob("authenticated-active", ScanJobType.ACTIVE_SCAN_AS_USER)));
        CountDownLatch duplicateCheckEntered = new CountDownLatch(1);
        CountDownLatch releaseDuplicateCheck = new CountDownLatch(1);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ScanJob> snapshot = (List<ScanJob>) invocation.callRealMethod();
            duplicateCheckEntered.countDown();
            await(releaseDuplicateCheck);
            return snapshot;
        }).when(firstStore).listStrict();

        try (ScanJobLaunchSafety first = safety(firstStore, "operator-a");
             ScanJobLaunchSafety second = safety(secondStore, "operator-b");
             var threads = Executors.newFixedThreadPool(2)) {
            var firstAttach = threads.submit(() -> first.reconcile("active", "attempt-active", 0,
                    "attach_scan", "shared-native-id", true));
            assertTrue(duplicateCheckEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
            var secondAttach = threads.submit(() -> assertThrows(IllegalStateException.class,
                    () -> second.reconcile("authenticated-active", "attempt-authenticated-active", 0,
                            "attach_scan", "shared-native-id", true)));
            assertTrue(awaitScanJobLockWait(), "The competing attachment must actually wait in PostgreSQL");
            releaseDuplicateCheck.countDown();
            assertEquals(ScanJobStatus.RUNNING, firstAttach.get(WAIT_SECONDS, TimeUnit.SECONDS).getStatus());
            IllegalStateException rejected = secondAttach.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(exceptionMessages(rejected).contains("already owned"));

            List<ScanJob> persisted = newStore().listStrict();
            assertEquals(1, persisted.stream().filter(job -> "shared-native-id".equals(job.getZapScanId())).count());
            ScanJob loser = secondStore.load("authenticated-active").orElseThrow();
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, loser.getStatus());
            assertEquals(0, loser.getLaunchAttempt().resolutionVersion());
            assertNull(loser.getZapScanId());
        } finally {
            releaseDuplicateCheck.countDown();
        }
    }

    @Test
    void stopEffectAndOperatorResolutionSerializeAcrossControllersAndRejectStaleStop() throws Exception {
        PostgresScanJobStore firstStore = newStore();
        PostgresScanJobStore secondStore = newStore();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger nativeStops = new AtomicInteger();
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        try (ScanJobLaunchSafety first = safety(firstStore, "worker-a")) {
            ScanJobLaunchSafety.StartAttempt start = first.newAttempt(
                    claim(firstStore, job("running", ScanJobType.ACTIVE_SCAN), "worker-a"));
            start.beforeDispatch();
            start.accepted("native-42");
            start.finished();
            ScanJob accepted = firstStore.load("running").orElseThrow();
            firstStore.updateLaunchAttempt(accepted.getId(), accepted.getLaunchAttempt().id(), 0, current -> {
                current.requestCancellation(now(), now().plusSeconds(60));
                return current;
            }).orElseThrow();
            ScanJobStopRequest stop = ScanJobStopRequest.owned(firstStore.load("running").orElseThrow());

            try (ScanJobLaunchSafety second = safety(secondStore, "operator-b");
                 var threads = Executors.newFixedThreadPool(2)) {
                var stopping = threads.submit(() -> first.executeOwnedStop(stop, () -> {
                    nativeStops.incrementAndGet();
                    events.add("native stop entered");
                    stopEntered.countDown();
                    await(releaseStop);
                    events.add("native stop returned");
                }));
                assertTrue(stopEntered.await(WAIT_SECONDS, TimeUnit.SECONDS));
                var resolving = threads.submit(() -> {
                    second.reconcile(stop.jobId(), stop.launchAttemptId(), stop.resolutionVersion(),
                            "confirm_stopped", null, true);
                    events.add("operator resolved");
                });
                assertTrue(awaitScanJobLockWait(), "Operator resolution must wait for the bounded native effect");
                releaseStop.countDown();
                stopping.get(WAIT_SECONDS, TimeUnit.SECONDS);
                resolving.get(WAIT_SECONDS, TimeUnit.SECONDS);
                assertEquals(List.of("native stop entered", "native stop returned", "operator resolved"), events);
                assertThrows(IllegalStateException.class,
                        () -> first.executeOwnedStop(stop, nativeStops::incrementAndGet));
            }
            ScanJob resolved = newStore().load("running").orElseThrow();
            assertEquals(ScanJobStatus.CANCELLED, resolved.getStatus());
            assertEquals(1, resolved.getLaunchAttempt().resolutionVersion());
            assertEquals(1, nativeStops.get());
        } finally {
            releaseStop.countDown();
        }
    }

    @Test
    void expiredLegacyClaimIsHeldWhileUnclaimedWorkRemainsDistinct() throws Exception {
        PostgresScanJobStore store = newStore();
        ScanJob legacy = job("legacy-claimed", ScanJobType.ACTIVE_SCAN);
        Instant past = now().minusSeconds(60);
        legacy.claim("departed-worker", past, past);
        store.upsertAll(List.of(legacy));
        store.admitQueuedJob(job("waiting-active", ScanJobType.ACTIVE_SCAN));
        store.admitQueuedJob(job("unclaimed-spider", ScanJobType.SPIDER_SCAN));

        try (ScanJobLaunchSafety replacement = safety(newStore(), "replacement-worker")) {
            ScanJob restored = newStore().load(legacy.getId()).orElseThrow();
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, restored.getStatus());
            assertNotNull(restored.getLaunchAttempt());
            assertEquals("legacy-unverified", restored.getLaunchAttempt().controllerId());
            assertNull(restored.getZapScanId());
            assertEquals(0, restored.getAttempts());
            List<ScanJob> claims = newStore().claimQueuedJobs("replacement-worker", now(), now().plusSeconds(60), 1, 1);
            assertEquals(List.of("unclaimed-spider"), claims.stream().map(ScanJob::getId).toList());
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, newStore().load(legacy.getId()).orElseThrow().getStatus());
            assertEquals(ScanJobStatus.QUEUED, newStore().load("waiting-active").orElseThrow().getStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyIncarnationJsonPreservesUnresolvedOwnershipAcrossRecoveryAndUpdates(boolean hasNativeId) throws Exception {
        String jobId = "legacy-json";
        String attemptId = "legacy-attempt";
        Instant dispatchedAt = Instant.parse("2026-01-01T00:00:00Z");
        String nativeId = hasNativeId ? "native-42" : null;
        ScanJob original = job(jobId, ScanJobType.ACTIVE_SCAN);
        original.beginLaunch(new ScanJob.LaunchAttempt(attemptId, "departed-controller", dispatchedAt, 7));
        if (hasNativeId) original.markRunning(nativeId);
        original.markLaunchUnknown("Outstanding legacy launch");
        Instant expired = now().minusSeconds(60);
        original.claim("departed-worker", expired, expired);
        PostgresScanJobStore originalStore = newStore();
        originalStore.upsertAll(List.of(original));
        originalStore.admitQueuedJob(job("waiting-active", ScanJobType.ACTIVE_SCAN));

        // Use the old on-disk JSON directly; the current serializer cannot generate this legacy field.
        String legacyJson = """
                {"id":"legacy-attempt","controllerId":"departed-controller",
                 "engineIncarnation":"legacy-operator-label-is-not-engine-proof",
                 "dispatchedAt":"2026-01-01T00:00:00Z","resolutionVersion":7}
                """;
        try (Connection connection = connection();
             var statement = connection.prepareStatement("UPDATE scan_jobs SET launch_attempt_json = ? WHERE job_id = ?")) {
            statement.setString(1, legacyJson);
            statement.setString(2, jobId);
            assertEquals(1, statement.executeUpdate());
        }

        PostgresScanJobStore reopened = newStore();
        ScanJob loaded = reopened.load(jobId).orElseThrow();
        assertEquals(original.getLaunchAttempt(), loaded.getLaunchAttempt());
        assertEquals("scan-client", loaded.getRequesterId());
        assertEquals(nativeId, loaded.getZapScanId());
        assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, loaded.getStatus());

        try (ScanJobLaunchSafety replacement = safety(reopened, "replacement-worker")) {
            ScanJob recovered = reopened.load(jobId).orElseThrow();
            assertEquals(original.getLaunchAttempt(), recovered.getLaunchAttempt());
            assertEquals(1, recovered.getAttempts());
            assertEquals(nativeId, recovered.getZapScanId());
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, recovered.getStatus());
            assertNoActiveAdmission(reopened, "replacement-worker");
            assertTrue(reopened.claimRunningJobs("replacement-worker", now(), now().plusSeconds(60)).isEmpty());

            ScanJob advanced = reopened.updateLaunchAttempt(jobId, attemptId, 7, current -> {
                current.restoreLaunchAttempt(current.getLaunchAttempt().resolvedBy("replacement-controller"));
                return current;
            }).orElseThrow();
            assertEquals(new ScanJob.LaunchAttempt(attemptId, "replacement-controller", dispatchedAt, 8),
                    advanced.getLaunchAttempt());
            assertFalse(new ObjectMapper().readTree(storedLaunchJson(jobId)).has("engineIncarnation"));

            AtomicInteger staleUpdates = new AtomicInteger();
            for (String candidateId : List.of(attemptId, "different-attempt")) {
                long candidateVersion = candidateId.equals(attemptId) ? 7 : 8;
                assertTrue(reopened.updateLaunchAttempt(jobId, candidateId, candidateVersion, current -> {
                    staleUpdates.incrementAndGet();
                    current.markCancelled();
                    return current;
                }).isEmpty());
            }
            assertEquals(0, staleUpdates.get());

            PostgresScanJobStore currentFormatStore = newStore();
            ScanJob reloaded = currentFormatStore.load(jobId).orElseThrow();
            assertEquals(advanced.getLaunchAttempt(), reloaded.getLaunchAttempt());
            assertEquals(ScanJobStatus.START_OUTCOME_UNKNOWN, reloaded.getStatus());
            assertEquals(nativeId, reloaded.getZapScanId());
            assertEquals("scan-client", reloaded.getRequesterId());
            assertEquals(1, reloaded.getAttempts());
            assertNoActiveAdmission(currentFormatStore, "another-worker");
            assertEquals(ScanJobStatus.QUEUED, currentFormatStore.load("waiting-active").orElseThrow().getStatus());
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

    private ScanJobLaunchSafety safety(PostgresScanJobStore store, String worker) throws Exception {
        ScanLaunchSafetyProperties properties = new ScanLaunchSafetyProperties();
        Path root = temporaryDirectory.toRealPath();
        properties.setJournalDirectory(root.resolve("unused-memory-journal").toString());
        ScanJobLaunchSafety safety = new ScanJobLaunchSafety(store, properties,
                root.resolve("reports").toString(), root.resolve("automation").toString());
        safety.configureWorker(worker, 60_000);
        safety.recover();
        return safety;
    }

    private ScanJob job(String id, ScanJobType type) {
        return new ScanJob(id, type, Map.of("targetUrl", "https://example.invalid/", "recurse", "true", "policy", ""),
                now(), 3, "scan-client", id);
    }

    private ScanJob heldJob(String id, ScanJobType type) {
        ScanJob job = job(id, type);
        job.beginLaunch(new ScanJob.LaunchAttempt("attempt-" + id, "departed-controller", now(), 0));
        job.markLaunchUnknown("Fake accepted launch with unavailable response");
        return job;
    }

    private ScanJobStartTarget claim(PostgresScanJobStore store, ScanJob job, String worker) {
        store.admitQueuedJob(job);
        ScanJob claimed = store.claimQueuedJobs(worker, now(), now().plusSeconds(60), 1, 1).getFirst();
        return new ScanJobStartTarget(claimed.getId(), claimed.getType(), claimed.getParameters(), ScanJobClaimToken.from(claimed));
    }

    private void expireClaim(PostgresScanJobStore store, ScanJob job) {
        store.updateLaunchAttempt(job.getId(), job.getLaunchAttempt().id(), job.getLaunchAttempt().resolutionVersion(), current -> {
            Instant past = now().minusSeconds(60);
            current.claim("worker-a", past, past);
            return current;
        }).orElseThrow();
    }

    private void assertNoActiveAdmission(PostgresScanJobStore store, String worker) {
        for (int cycle = 0; cycle < 3; cycle++) {
            Instant afterLease = now().plusSeconds(120 + cycle);
            assertTrue(store.claimQueuedJobs(worker, afterLease, afterLease.plusSeconds(60), 1, 0).isEmpty());
        }
    }

    private ScanJobResultApplier applier(PostgresScanJobStore store, String worker) {
        ScanJobRetryPolicy retry = new ScanJobRetryPolicy(3, 0, 0, 1);
        return new ScanJobResultApplier(store, worker,
                new ScanJobClaimManager(store, worker, null), retry, retry);
    }

    private ScanJobDispatcher dispatcher(ScanJobLaunchSafety safety, Function<NativeScanLaunch, String> nativeStart) {
        ScanJobRuntimeExecutor fakeEngine = new ScanJobRuntimeExecutor(null, null, null) {
            @Override public String startScan(ScanJobStartTarget target, NativeScanLaunch launch) {
                return nativeStart.apply(launch);
            }
        };
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(fakeEngine, Executors.newSingleThreadExecutor(), Duration.ofSeconds(5));
        dispatcher.setLaunchSafety(safety);
        return dispatcher;
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String storedLaunchJson(String jobId) throws Exception {
        try (Connection connection = connection();
             var statement = connection.prepareStatement("SELECT launch_attempt_json FROM scan_jobs WHERE job_id = ?")) {
            statement.setString(1, jobId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private boolean awaitScanJobLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (var result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                        + "AND wait_event_type = 'Lock' AND query LIKE '%scan_jobs%')")) {
                    result.next();
                    if (result.getBoolean(1)) return true;
                }
                Thread.sleep(10);
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for the controlled test interleaving");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) messages.append(current.getMessage()).append('\n');
        return messages.toString();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
