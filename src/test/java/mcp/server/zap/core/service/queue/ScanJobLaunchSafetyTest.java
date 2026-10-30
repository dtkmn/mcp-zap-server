package mcp.server.zap.core.service.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanJobLaunchSafetyTest {
    @TempDir Path temporary;

    @Test
    void legacyJsonIgnoresOnlyUnusedLabelAndRetainsOwnershipValidation() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        String legacy = """
                {"id":"attempt","controllerId":"controller","engineIncarnation":"old-label",
                 "dispatchedAt":"2026-01-01T00:00:00Z","resolutionVersion":7}
                """;
        var attempt = mapper.readValue(legacy, ScanJob.LaunchAttempt.class);
        assertThat(attempt).isEqualTo(new ScanJob.LaunchAttempt("attempt", "controller",
                Instant.parse("2026-01-01T00:00:00Z"), 7));
        assertThat(mapper.writeValueAsString(attempt)).doesNotContain("engineIncarnation");
        assertThatThrownBy(() -> mapper.readValue(legacy.replace("old-label", "x")
                .replace("engineIncarnation", "unexpectedField"), ScanJob.LaunchAttempt.class)).isInstanceOf(Exception.class);
        for (String invalid : List.of(legacy.replace("\"attempt\"", "\"\""),
                legacy.replace("\"controller\"", "null"),
                legacy.replace("\"2026-01-01T00:00:00Z\"", "null"),
                legacy.replace(":7", ":-1"))) {
            assertThatThrownBy(() -> mapper.readValue(invalid, ScanJob.LaunchAttempt.class)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void acceptedStartWithLostReplyDoesNotRetryOrReleaseCapacityAcrossControllerRestart() throws Exception {
        AtomicInteger nativeStarts = new AtomicInteger();
        InMemoryScanJobStore firstStore = new InMemoryScanJobStore();
        String jobId;
        try (ScanJobLaunchSafety first = safety(firstStore, "state", "worker-a")) {
            ScanJobStartTarget target = claim(firstStore, "job", "worker-a");
            jobId = target.jobId();
            try (ScanJobDispatcher dispatcher = dispatcher(first, launch -> {
                launch.beforeDispatch();
                nativeStarts.incrementAndGet();
                throw new IllegalStateException("fake engine accepted the start but its reply was lost");
            })) {
                ScanJobDispatchResult result = dispatcher.dispatch(work(target));
                ScanJobResultApplier applier = applier(firstStore, "worker-a");
                applier.applyResults(result.pollResults(), result.startResults(), Instant.now().plusSeconds(30));
            }
            ScanJob held = firstStore.load(jobId).orElseThrow();
            assertThat(held.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
            assertThat(held.getAttempts()).isEqualTo(1);
            firstStore.admitQueuedJob(job("later"));
            for (int cycle = 0; cycle < 3; cycle++) {
                Instant afterLease = Instant.now().plusSeconds(120 + cycle);
                assertThat(firstStore.claimQueuedJobs("replacement", afterLease, afterLease.plusSeconds(30), 1, 1)).isEmpty();
            }
        }
        InMemoryScanJobStore restartedStore = new InMemoryScanJobStore();
        try (ScanJobLaunchSafety restarted = safety(restartedStore, "state", "worker-a")) {
            assertThat(restartedStore.load(jobId).orElseThrow().getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
            assertThat(restarted.admissionFenced()).isTrue();
            assertThatThrownBy(restarted::requireAdmissionSafe).hasMessageContaining("operator reconciliation");
            restartedStore.admitQueuedJob(job("new-after-restart"));
            Instant later = Instant.now().plusSeconds(1000);
            assertThat(restartedStore.claimQueuedJobs("replacement", later, later.plusSeconds(30), 1, 1)).isEmpty();
        }
        assertThat(nativeStarts).hasValue(1);
    }

    @Test
    void intentPersistenceFailurePreventsNativeEffect() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore() {
            @Override public java.util.Optional<ScanJob> updateClaimedJob(String id, ScanJobClaimToken token,
                    Instant now, java.util.function.UnaryOperator<ScanJob> update) {
                throw new IllegalStateException("fake persistence failure before native start");
            }
        };
        AtomicInteger effects = new AtomicInteger();
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobStartTarget target = claim(store, "job", "worker");
            try (ScanJobDispatcher dispatcher = dispatcher(safety, launch -> {
                launch.beforeDispatch();
                effects.incrementAndGet();
                launch.accepted("1");
                return "1";
            })) {
                dispatcher.dispatch(work(target));
            }
        }
        assertThat(effects).hasValue(0);
    }

    @Test
    void lateAcceptedIdAdoptsSameAttemptAndPreservesCancellation() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobStartTarget target = claim(store, "job", "worker");
            try (ScanJobDispatcher dispatcher = dispatcher(safety, launch -> {
                launch.beforeDispatch();
                starts.incrementAndGet();
                entered.countDown();
                awaitIgnoringInterrupt(release);
                try { launch.accepted("native-1"); return "native-1"; }
                finally { returned.countDown(); }
            })) {
                dispatcher.dispatch(work(target));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                ScanJob held = store.load("job").orElseThrow();
                String attempt = held.getLaunchAttempt().id();
                assertThat(held.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
                held.requestCancellation(Instant.now(), Instant.now().plusSeconds(30));
                release.countDown();
                assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
                ScanJob adopted = store.load("job").orElseThrow();
                assertThat(adopted.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(adopted.getZapScanId()).isEqualTo("native-1");
                assertThat(adopted.getLaunchAttempt().id()).isEqualTo(attempt);
                assertThat(adopted.isCancellationRequested()).isTrue();
                assertThat(adopted.getAttempts()).isEqualTo(1);
            }
        } finally { release.countDown(); }
        assertThat(starts).hasValue(1);
    }

    @Test
    void lateAuthoritativeBusyReplyReleasesExactUnacceptedAttempt() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobStartTarget target = claim(store, "job", "worker");
            try (ScanJobDispatcher dispatcher = dispatcher(safety, launch -> {
                launch.beforeDispatch();
                awaitIgnoringInterrupt(release);
                throw new EngineBusyException("explicit fake native rejection", null);
            }, returned)) {
                dispatcher.dispatch(work(target));
                assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
                release.countDown();
                assertThat(returned.await(2, TimeUnit.SECONDS)).isTrue();
                // The fake executor has returned; wait on the safety state transition, not elapsed time.
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                        assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.QUEUED));
                assertThat(store.load("job").orElseThrow().getAttempts()).isZero();
                assertThat(store.load("job").orElseThrow().getLaunchAttempt()).isNull();
            }
        } finally { release.countDown(); }
    }

    @Test
    void staleCallbackAndStopCannotActAfterOperatorAdvancesResolutionVersion() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        AtomicInteger stops = new AtomicInteger();
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobStartTarget target = claim(store, "job", "worker");
            ScanJobLaunchSafety.StartAttempt attempt = safety.newAttempt(target);
            attempt.beforeDispatch();
            attempt.failed();
            attempt.finished();
            ScanJob held = store.load("job").orElseThrow();
            String id = held.getLaunchAttempt().id();
            safety.reconcile("job", id, 0, "confirm_stopped", null, true);
            assertThatThrownBy(() -> attempt.accepted("reused-native-id")).hasMessageContaining("ownership changed");
            assertThatThrownBy(() -> safety.executeOwnedStop(
                    new ScanJobStopRequest(ScanJobType.ACTIVE_SCAN, "reused-native-id", "job", id, 0), stops::incrementAndGet))
                    .hasMessageContaining("receipt changed");
            assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.CANCELLED);
            assertThat(store.load("job").orElseThrow().getLaunchAttempt().resolutionVersion()).isEqualTo(1);
            assertThat(stops).hasValue(0);
        }
    }

    @Test
    void outerStartResultCannotCreateLegacyCleanupForAnAlreadyHeldReceipt() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobStartTarget target = claim(store, "job", "worker");
            ScanJobLaunchSafety.StartAttempt attempt = safety.newAttempt(target);
            attempt.beforeDispatch();
            attempt.accepted("7");
            attempt.finished();
            ScanJob job = store.load("job").orElseThrow();
            store.updateLaunchAttempt("job", job.getLaunchAttempt().id(), 0, current -> {
                current.markLaunchUnknown("fake controller ownership loss between receipt and result application");
                return current;
            });
            ScanJobApplyOutcome applied = applier(store, "worker").applyResults(List.of(),
                    List.of(ScanJobStartResult.success(target, "7")), Instant.now().plusSeconds(30));
            assertThat(applied.stopRequests()).isEmpty();
            assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
        }
    }

    @Test
    void liveSiblingReceiptSurvivesJoinButExpiredForeignOwnershipBecomesHeld() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        try (ScanJobLaunchSafety first = safety(store, "first", "worker-a")) {
            ScanJobLaunchSafety.StartAttempt launch = first.newAttempt(claim(store, "job", "worker-a"));
            launch.beforeDispatch(); launch.accepted("8"); launch.finished();
            try (ScanJobLaunchSafety sibling = safety(store, "second", "worker-b")) {
                assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                ScanJob job = store.load("job").orElseThrow();
                store.updateLaunchAttempt("job", job.getLaunchAttempt().id(), 0, current -> {
                    Instant past = Instant.now().minusSeconds(60);
                    current.claim("worker-a", past, past);
                    return current;
                });
                sibling.refreshHolds();
                assertThat(store.load("job").orElseThrow().getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
                assertThat(store.claimRunningJobs("worker-b", Instant.now(), Instant.now().plusSeconds(30))).isEmpty();
            }
        }
    }

    @Test
    void stopEffectIsSerializedWithReconciliationAndStaleStopIsRejected() throws Exception {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        try (ScanJobLaunchSafety safety = safety(store, "state", "worker")) {
            ScanJobLaunchSafety.StartAttempt attempt = safety.newAttempt(claim(store, "job", "worker"));
            attempt.beforeDispatch(); attempt.accepted("9"); attempt.finished();
            ScanJob job = store.load("job").orElseThrow();
            job.requestCancellation(Instant.now(), Instant.now().plusSeconds(30));
            ScanJobStopRequest stop = ScanJobStopRequest.owned(job);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var stopping = threads.submit(() -> safety.executeOwnedStop(stop, () -> {
                    events.add("stop entered"); stopEntered.countDown(); awaitIgnoringInterrupt(releaseStop); events.add("stop returned");
                }));
                assertThat(stopEntered.await(2, TimeUnit.SECONDS)).isTrue();
                var resolving = threads.submit(() -> {
                    safety.reconcile("job", stop.launchAttemptId(), 0, "confirm_stopped", null, true);
                    events.add("operator resolved");
                });
                releaseStop.countDown();
                stopping.get(2, TimeUnit.SECONDS); resolving.get(2, TimeUnit.SECONDS);
            }
            assertThat(events).containsExactly("stop entered", "stop returned", "operator resolved");
            assertThatThrownBy(() -> safety.executeOwnedStop(stop, () -> events.add("unsafe stale stop")))
                    .hasMessageContaining("receipt changed");
        } finally { releaseStop.countDown(); }
    }

    private ScanJobLaunchSafety safety(InMemoryScanJobStore store, String state, String worker) throws java.io.IOException {
        ScanLaunchSafetyProperties properties = new ScanLaunchSafetyProperties();
        Path root = temporary.toRealPath();
        properties.setJournalDirectory(root.resolve(state).toString());
        ScanJobLaunchSafety safety = new ScanJobLaunchSafety(store, properties,
                root.resolve("reports").toString(), root.resolve("automation").toString());
        safety.configureWorker(worker, 15000);
        safety.recover();
        return safety;
    }

    private ScanJob job(String id) {
        return new ScanJob(id, ScanJobType.ACTIVE_SCAN, Map.of("targetUrl", "https://example.invalid"),
                Instant.now(), 3, "requester", null);
    }

    private ScanJobStartTarget claim(InMemoryScanJobStore store, String id, String worker) {
        store.admitQueuedJob(job(id));
        ScanJob job = store.claimQueuedJobs(worker, Instant.now(), Instant.now().plusSeconds(15), 1, 1).getFirst();
        return new ScanJobStartTarget(id, job.getType(), job.getParameters(), ScanJobClaimToken.from(job));
    }

    private ScanJobWorkPlan work(ScanJobStartTarget target) { return new ScanJobWorkPlan(List.of(), List.of(target)); }

    private ScanJobResultApplier applier(InMemoryScanJobStore store, String worker) {
        ScanJobRetryPolicy policy = new ScanJobRetryPolicy(3, 0, 0, 1);
        ScanJobResultApplier applier = new ScanJobResultApplier(store, worker,
                new ScanJobClaimManager(store, worker, null), policy, policy);
        return applier;
    }

    private ScanJobDispatcher dispatcher(ScanJobLaunchSafety safety, Function<NativeScanLaunch, String> action) {
        return dispatcher(safety, action, null);
    }

    private ScanJobDispatcher dispatcher(ScanJobLaunchSafety safety, Function<NativeScanLaunch, String> action,
                                         CountDownLatch returned) {
        ScanJobRuntimeExecutor fake = new ScanJobRuntimeExecutor(null, null, null) {
            @Override public String startScan(ScanJobStartTarget target, NativeScanLaunch launch) {
                try { return action.apply(launch); }
                finally { if (returned != null) returned.countDown(); }
            }
        };
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(fake, Executors.newSingleThreadExecutor(), Duration.ofMillis(100));
        dispatcher.setLaunchSafety(safety);
        return dispatcher;
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fake engine latch timed out"); return; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
