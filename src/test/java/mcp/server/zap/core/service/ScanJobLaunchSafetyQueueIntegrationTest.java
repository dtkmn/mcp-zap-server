package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.core.service.queue.ScanJobLaunchSafety;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

class ScanJobLaunchSafetyQueueIntegrationTest {
    private static final String TARGET = "https://example.invalid";
    @TempDir Path temporary;

    @AfterEach
    void clearRequestIdentity() {
        RequestIdentityHolder.clear();
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void lostNativeReplyAndPublicCancellationRetainCapacityWithoutRedispatch(ScanJobType type) throws Exception {
        try (Fixture fixture = fixture(launch -> {
            throw new IllegalStateException("Fake native engine accepted the start, but its reply was lost");
        })) {
            String response = enqueue(fixture.queue, type, "first");
            ScanJob held = fixture.onlyJob();
            String id = held.getId();
            String attempt = held.getLaunchAttempt().id();
            assertThat(response).contains(id);
            assertThat(held.getType()).isEqualTo(type);
            assertThat(held.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
            assertThat(held.getZapScanId()).isNull();

            fixture.queue.cancelScanJob(id);
            ScanJob cancelledBeforeId = fixture.job(id);
            assertThat(cancelledBeforeId.getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
            assertThat(cancelledBeforeId.isCancellationRequested()).isTrue();
            assertThat(cancelledBeforeId.getStatus().isTerminal()).isFalse();
            assertThat(fixture.nativeStops).hasValue(0);

            enqueue(fixture.queue, type, "follower");
            String followerId = fixture.otherJob(id).getId();
            fixture.expireAndLoseWorkerClaim(id);
            for (int cycle = 0; cycle < 3; cycle++) fixture.queue.processQueue();

            assertThat(fixture.job(id).getStatus()).isEqualTo(ScanJobStatus.START_OUTCOME_UNKNOWN);
            assertThat(fixture.job(id).getLaunchAttempt().id()).isEqualTo(attempt);
            assertThat(fixture.job(id).getAttempts()).isEqualTo(1);
            assertThat(fixture.job(id).isCancellationRequested()).isTrue();
            assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
            assertThat(fixture.nativeStarts).hasValue(1);
            assertThat(fixture.nativeStops).hasValue(0);
        }
    }

    @ParameterizedTest
    @EnumSource(ScanJobType.class)
    void cancellationBeforeIdSurvivesClaimLossAndLateIdWithoutPrematureRelease(ScanJobType type) throws Exception {
        CountDownLatch nativeStartEntered = new CountDownLatch(1);
        CountDownLatch releaseReply = new CountDownLatch(1);
        String nativeId = "native-" + type.name().toLowerCase(java.util.Locale.ROOT);
        try (Fixture fixture = fixture(launch -> {
            nativeStartEntered.countDown();
            if (!releaseReply.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fake native reply was not released by the test");
            }
            launch.accepted(nativeId);
            return nativeId;
        }); var caller = Executors.newSingleThreadExecutor()) {
            var submission = caller.submit(() -> enqueue(fixture.queue, type, "first"));
            try {
                assertThat(nativeStartEntered.await(2, TimeUnit.SECONDS)).isTrue();
                ScanJob starting = fixture.onlyJob();
                String id = starting.getId();
                String attempt = starting.getLaunchAttempt().id();
                assertThat(starting.getStatus()).isEqualTo(ScanJobStatus.STARTING);

                fixture.queue.cancelScanJob(id);
                assertThat(fixture.job(id).isCancellationRequested()).isTrue();
                assertThat(fixture.job(id).getZapScanId()).isNull();
                assertThat(fixture.job(id).getStatus().isTerminal()).isFalse();
                enqueue(fixture.queue, type, "follower");
                String followerId = fixture.otherJob(id).getId();
                fixture.expireAndLoseWorkerClaim(id);
                for (int cycle = 0; cycle < 2; cycle++) fixture.queue.processQueue();
                assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(fixture.nativeStarts).hasValue(1);
                assertThat(fixture.nativeStops).hasValue(0);

                releaseReply.countDown();
                assertThat(submission.get(3, TimeUnit.SECONDS)).contains(id);
                ScanJob adopted = fixture.job(id);
                assertThat(adopted.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(adopted.getZapScanId()).isEqualTo(nativeId);
                assertThat(adopted.getLaunchAttempt().id()).isEqualTo(attempt);
                assertThat(adopted.isCancellationRequested()).isTrue();
                assertThat(adopted.getAttempts()).isEqualTo(1);
                assertThat(fixture.nativeStarts).hasValue(1);
                assertThat(fixture.nativeStops).hasValue(0);

                // Acknowledging a stop without a positive termination status must retain ownership.
                fixture.queue.processQueue();
                assertThat(fixture.nativeStops.get()).isGreaterThan(0);
                assertThat(fixture.job(id).getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(fixture.job(id).isCancellationRequested()).isTrue();
                assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(fixture.nativeStarts).hasValue(1);

                fixture.nativeTerminated.set(true);
                fixture.queue.cancelScanJob(id);
                assertThat(fixture.job(id).getStatus()).isEqualTo(ScanJobStatus.CANCELLED);
                assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(fixture.nativeStarts).hasValue(2);
            } finally {
                releaseReply.countDown();
            }
        }
    }

    @Test
    void concurrentQueueActivityDoesNotMisclassifyOwnPreflightClaimAsLegacyLaunch() throws Exception {
        CountDownLatch preflightEntered = new CountDownLatch(1);
        CountDownLatch releasePreflight = new CountDownLatch(1);
        try (Fixture fixture = fixture(() -> {
            preflightEntered.countDown();
            if (!releasePreflight.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Fake preflight was not released by the test");
            }
        }, launch -> {
            launch.accepted("native-configured");
            return "native-configured";
        }); var caller = Executors.newSingleThreadExecutor()) {
            var submission = caller.submit(() -> enqueue(fixture.queue, ScanJobType.ACTIVE_SCAN, "first"));
            try {
                assertThat(preflightEntered.await(2, TimeUnit.SECONDS)).isTrue();
                ScanJob configuring = fixture.onlyJob();
                String id = configuring.getId();
                assertThat(configuring.getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(configuring.hasLiveClaim(Instant.now())).isTrue();
                assertThat(configuring.getLaunchAttempt()).isNull();
                assertThat(fixture.nativeStarts).hasValue(0);

                assertThat(fixture.queue.getScanJobStatus(id)).contains(id);
                enqueue(fixture.queue, ScanJobType.ACTIVE_SCAN, "follower");
                String followerId = fixture.otherJob(id).getId();
                fixture.queue.processQueue();

                assertThat(fixture.job(id).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(fixture.job(id).getLaunchAttempt()).isNull();
                assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(fixture.nativeStarts).hasValue(0);
                assertThat(fixture.nativeStops).hasValue(0);

                releasePreflight.countDown();
                assertThat(submission.get(3, TimeUnit.SECONDS)).contains(id);
                assertThat(fixture.job(id).getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(fixture.job(id).getZapScanId()).isEqualTo("native-configured");
                assertThat(fixture.job(id).getLaunchAttempt()).isNotNull();
                assertThat(fixture.job(id).getAttempts()).isEqualTo(1);
                assertThat(fixture.job(followerId).getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                assertThat(fixture.nativeStarts).hasValue(1);
                assertThat(fixture.nativeStops).hasValue(0);
            } finally {
                releasePreflight.countDown();
            }
        }
    }

    private static String enqueue(ScanJobQueueService queue, ScanJobType type, String key) {
        return switch (type) {
            case ACTIVE_SCAN -> queue.queueActiveScan(TARGET, "true", null, key);
            case ACTIVE_SCAN_AS_USER -> queue.queueActiveScanAsUser("context-1", "user-1", TARGET,
                    "true", null, key);
            case SPIDER_SCAN -> queue.queueSpiderScan(TARGET, key);
            case SPIDER_SCAN_AS_USER -> queue.queueSpiderScanAsUser("context-1", "user-1", TARGET,
                    "10", "true", "false", key);
            case AJAX_SPIDER -> queue.queueAjaxSpiderScan(TARGET, key);
            case CLIENT_SPIDER -> queue.queueClientSpiderScan(TARGET, 4, key);
        };
    }

    private Fixture fixture(StartReply reply) throws Exception {
        return fixture(() -> { }, reply);
    }

    private Fixture fixture(StartPreflight preflight, StartReply reply) throws Exception {
        Path root = temporary.toRealPath();
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicBoolean terminated = new AtomicBoolean();
        Answer<Object> nativeBoundary = invocation -> {
            Object[] arguments = invocation.getArguments();
            if (arguments.length > 0 && arguments[arguments.length - 1] instanceof NativeScanLaunch launch) {
                preflight.prepare();
                launch.beforeDispatch();
                starts.incrementAndGet();
                return reply.receive(launch);
            }
            String method = invocation.getMethod().getName();
            if (method.equals("stopActiveScanJob") || method.equals("stopSpiderScanJob")
                    || method.equals("stopClientSpiderJob") || method.equals("stopAjaxSpiderJob")) {
                stops.incrementAndGet();
                return null;
            }
            if (method.equals("getActiveScanProgressPercent") || method.equals("getSpiderScanProgressPercent")
                    || method.equals("getClientSpiderProgressPercent")) return terminated.get() ? 100 : 0;
            if (method.equals("isAjaxSpiderRunning")) return !terminated.get();
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        };
        ScanLimitProperties limits = new ScanLimitProperties();
        limits.setMaxConcurrentActiveScans(1);
        limits.setMaxConcurrentSpiderScans(1);
        ScanJobQueueService queue = new ScanJobQueueService(mock(ActiveScanService.class, nativeBoundary),
                mock(SpiderScanService.class, nativeBoundary), mock(AjaxSpiderService.class, nativeBoundary),
                mock(ClientSpiderService.class, nativeBoundary), mock(UrlValidationService.class), limits,
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1),
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1), false, store,
                new SingleNodeQueueLeadershipCoordinator());
        ScanLaunchSafetyProperties properties = new ScanLaunchSafetyProperties();
        properties.setJournalDirectory(root.resolve("launch-state").toString());
        ScanJobLaunchSafety safety = new ScanJobLaunchSafety(store, properties,
                root.resolve("reports").toString(), root.resolve("automation").toString());
        queue.configureLaunchSafety(safety);
        return new Fixture(queue, store, safety, starts, stops, terminated);
    }

    @FunctionalInterface
    private interface StartPreflight {
        void prepare() throws Exception;
    }

    @FunctionalInterface
    private interface StartReply {
        String receive(NativeScanLaunch launch) throws Exception;
    }

    private record Fixture(ScanJobQueueService queue, InMemoryScanJobStore store, ScanJobLaunchSafety safety,
                           AtomicInteger nativeStarts, AtomicInteger nativeStops,
                           AtomicBoolean nativeTerminated) implements AutoCloseable {
        ScanJob onlyJob() {
            assertThat(store.list()).hasSize(1);
            return store.list().getFirst();
        }

        ScanJob job(String id) {
            return store.load(id).orElseThrow();
        }

        ScanJob otherJob(String id) {
            List<ScanJob> others = store.list().stream().filter(job -> !job.getId().equals(id)).toList();
            assertThat(others).hasSize(1);
            return others.getFirst();
        }

        void expireAndLoseWorkerClaim(String id) {
            ScanJob job = job(id);
            store.updateLaunchAttempt(id, job.getLaunchAttempt().id(), job.getLaunchAttempt().resolutionVersion(), current -> {
                Instant past = Instant.now().minusSeconds(60);
                current.claim("departed-worker", past, past);
                return current;
            }).orElseThrow();
        }

        @Override
        public void close() throws Exception {
            queue.shutdownExecutor();
            safety.close();
        }
    }
}
