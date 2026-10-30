package mcp.server.zap.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApiException;

class EngineMaintenanceGateTest {
    @TempDir Path directory;
    AddonMaintenanceProperties properties;
    EngineMaintenanceGate gate;

    @BeforeEach void setup() {
        properties = new AddonMaintenanceProperties();
        properties.setStateDirectory(directory.toString());
        properties.setUpdatesEnabled(true);
        gate = new EngineMaintenanceGate(properties);
    }

    @Test void runningTransportActionPreventsMaintenanceAndCompletesNormally() throws Exception {
        CountDownLatch running = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger maintenanceCalls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var action = executor.submit(() -> gate.apiCall("openapi", "action", "importUrl", () -> {
                running.countDown();
                try { assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new ClientApiException(e); }
                return "OK";
            }));
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> gate.withMaintenance(maintenanceCalls::incrementAndGet))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Engine actions are in progress; retry after they finish.");
                assertThat(maintenanceCalls.get()).isZero();
            } finally { finish.countDown(); }
            assertThat(action.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void maintenanceBlocksEveryActionFamilyButLeavesReadOnlyStatusAvailable() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            gate.withMaintenance(() -> {
                var probe = executor.submit(() -> {
                    for (String component : new String[]{"spider", "ascan", "ajaxSpider", "clientSpider", "automation", "openapi", "authentication", "core", "reports"}) {
                        assertThatThrownBy(() -> gate.apiCall(component, "action", "start", () -> requests.incrementAndGet()))
                                .isInstanceOf(EngineBusyException.class);
                    }
                    assertThat(gate.apiCall("pscan", "view", "scanners", () -> "visible")).isEqualTo("visible");
                    return null;
                });
                assertDoesNotThrow(() -> probe.get(5, TimeUnit.SECONDS));
                assertThat(requests.get()).isZero();
                return null;
            });
        }
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void unresolvedUpdateBlocksAfterRestartEvenWhenUpdatesAreDisabled() throws Exception {
        gate.withMaintenance(() -> { gate.markUpdatePending("pscanrules", "77.0.0"); return null; });
        properties.setUpdatesEnabled(false);
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertThatThrownBy(() -> restored.apiCall("core", "action", "accessUrl", () -> "bad"))
                .isInstanceOf(ClientApiException.class);
        assertThat(restored.apiCall("autoupdate", "view", "installedAddons", () -> "visible")).isEqualTo("visible");
        assertThatThrownBy(() -> restored.withMaintenance(() -> null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void durableBrowserAndPlanIdsSurviveControllerRestartUntilCompletionIsVerified() throws Exception {
        gate.apiCall("clientSpider", "action", "scan", () -> new ApiResponseElement("scan", "42"));
        gate.apiCall("automation", "action", "runPlan", () -> new ApiResponseElement("planId", "51"));
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.trackedActivities()).containsKeys("clientSpider:42", "automation:51");
        restored.completedActivity("clientSpider:42");
        assertThat(new EngineMaintenanceGate(properties).trackedActivities()).containsOnlyKeys("automation:51");
    }

    @Test void ambiguousActivityStartPersistsUnknownStateAndPreventsUnsafeLaterWork() {
        assertThatThrownBy(() -> gate.apiCall("automation", "action", "runPlan", () -> {
            throw new ClientApiException("read timeout");
        })).isInstanceOf(ClientApiException.class);
        assertThat(gate.isQuarantined()).isTrue();
        assertThat(new EngineMaintenanceGate(properties).isQuarantined()).isTrue();
        assertThatThrownBy(() -> gate.apiCall("ascan", "action", "scan", () -> "bad"))
                .isInstanceOf(ClientApiException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"openapi", "authentication", "spider", "ascan", "core"})
    void anyAmbiguousActionPersistsQuarantineBeforeDispatch(String component) throws Exception {
        assertThatThrownBy(() -> gate.apiCall(component, "action", "start", () -> {
            // The pending receipt must already exist if the MCP process crashes inside HTTP I/O.
            assertThat(org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> Files.readString(directory.resolve("activities")))).contains(component + ":pending-");
            throw new ClientApiException("request timeout; engine may continue");
        })).isInstanceOf(ClientApiException.class);
        assertThat(gate.isQuarantined()).isTrue();
        assertThatThrownBy(() -> gate.withMaintenance(() -> null)).isInstanceOf(IllegalStateException.class);
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertThatThrownBy(() -> restored.withMaintenance(() -> null)).isInstanceOf(IllegalStateException.class);
        assertThat(restored.apiCall("pscan", "view", "scanners", () -> "visible")).isEqualTo("visible");
    }

    @ParameterizedTest @CsvSource({
            "spider,scan,scan_in_progress", "spider,scanAsUser,scan_in_progress",
            "ascan,scan,scan_in_progress", "ascan,scanAsUser,scan_in_progress",
            "ajaxSpider,scan,scan_in_progress", "clientSpider,scan,scan_in_progress",
            "clientSpider,setOptionMaxDuration,bad_action", "openapi,importUrl,no_implementor"
    })
    void definiteNoStartRejectionsPreserveQueueRetryAndOrdinaryFutureActions(String component, String method, String code) throws Exception {
        assertThatThrownBy(() -> gate.apiCall(component, "action", method, () -> {
            throw new ClientApiException("rejected", code, null);
        })).isInstanceOf(ClientApiException.class);
        assertThat(gate.isQuarantined()).isFalse();
        assertThat(Files.readString(directory.resolve("activities"))).isEmpty();
        assertThat(new EngineMaintenanceGate(properties).isQuarantined()).isFalse();
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @ParameterizedTest @ValueSource(strings = {"internal_error", "illegal_parameter", "unknown", "scan_in_progress"})
    void otherHandlerErrorsRemainAmbiguousEvenIfTheyHaveStructuredCodes(String code) {
        assertThatThrownBy(() -> gate.apiCall("openapi", "action", "importUrl", () -> {
            throw new ClientApiException("may have partially processed", code, null);
        })).isInstanceOf(ClientApiException.class);
        assertThat(gate.isQuarantined()).isTrue();
        assertThat(new EngineMaintenanceGate(properties).isQuarantined()).isTrue();
    }

    @Test void malformedPersistedStateFailsClosedAndNeverBecomesAnApiParameter() throws Exception {
        Files.writeString(directory.resolve("activities"), "automation:../bad\n");
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertThatThrownBy(() -> restored.withMaintenance(() -> null)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "relative/state"})
    void rejectsStatePathsThatCouldWriteReceiptsIntoTheWorkingCheckout(String path) {
        properties.setStateDirectory(path);
        assertThatThrownBy(() -> new EngineMaintenanceGate(properties)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void normalStatusPollingPrunesOnlyMeasuredCompletedActivity() throws Exception {
        gate.apiCall("clientSpider", "action", "scan", () -> new ApiResponseElement("scan", "42"));
        gate.apiCall("clientSpider", "view", "status", Map.of("scanId", "42"), () -> new ApiResponseElement("status", "99"));
        assertThat(gate.trackedActivities()).containsKey("clientSpider:42");
        gate.apiCall("clientSpider", "view", "status", Map.of("scanId", "42"), () -> new ApiResponseElement("status", "100"));
        assertThat(gate.trackedActivities()).isEmpty();
        assertThat(new EngineMaintenanceGate(properties).trackedActivities()).isEmpty();
    }

    @Test void pendingPersistenceFailureRefusesDispatchAndReleasesTheActionLock() throws Exception {
        Files.createDirectory(directory.resolve("activities.tmp"));
        AtomicInteger requests = new AtomicInteger();

        assertThatThrownBy(() -> gate.apiCall("openapi", "action", "importUrl", requests::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot persist engine maintenance state; engine actions remain quarantined.");

        assertThat(requests.get()).isZero();
        assertThat(gate.isQuarantined()).isTrue();
        assertThat(gate.trackedActivities()).hasSize(1);
        assertThat(gate.trackedActivities().keySet().iterator().next()).startsWith("openapi:pending-");
        assertThat(directory.resolve("activities")).doesNotExist();
        assertQuarantineAndLockReleaseFromAnotherThread(gate);
    }

    @Test void resultPersistenceFailureKeepsThePendingReceiptAcrossRestartWithoutRedispatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();

        assertThatThrownBy(() -> gate.apiCall("automation", "action", "runPlan", () -> {
            requests.incrementAndGet();
            assertThat(assertDoesNotThrow(() -> Files.readString(directory.resolve("activities"))))
                    .contains("automation:pending-");
            assertDoesNotThrow(() -> Files.createDirectory(directory.resolve("activities.tmp")));
            return new ApiResponseElement("planId", "51");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot persist engine maintenance state; engine actions remain quarantined.");

        assertThat(requests.get()).isEqualTo(1);
        assertThat(gate.isQuarantined()).isTrue();
        assertThat(gate.trackedActivities()).containsOnlyKeys("automation:51");
        assertThat(Files.readString(directory.resolve("activities"))).contains("automation:pending-")
                .doesNotContain("automation:51");
        assertQuarantineAndLockReleaseFromAnotherThread(gate);
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertQuarantineAndLockReleaseFromAnotherThread(restored);
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({
            "2026-10-29T00:00:00Z,51,true",
            "'',51,false",
            "not-a-timestamp,51,false",
            "2026-10-29T00:00:00Z,99,false"
    })
    void planPollingPrunesOnlyARecordedIdWithAValidFinishedTimestamp(String finished, String planId,
                                                                   boolean completed) throws Exception {
        gate.apiCall("automation", "action", "runPlan", () -> new ApiResponseElement("planId", "51"));
        gate.apiCall("clientSpider", "action", "scan", () -> new ApiResponseElement("scan", "42"));

        gate.apiCall("automation", "view", "planProgress", Map.of("planId", planId),
                () -> new ApiResponseSet("planProgress", Map.of("finished", new ApiResponseElement("finished", finished))));

        if (completed) assertThat(gate.trackedActivities()).containsOnlyKeys("clientSpider:42");
        else assertThat(gate.trackedActivities()).containsOnlyKeys("automation:51", "clientSpider:42");
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.trackedActivities()).isEqualTo(gate.trackedActivities());
        assertThat(restored.isQuarantined()).isFalse();
    }

    @Test void maintenanceReturnsResultAndReleasesForAnotherThread() throws Exception {
        assertThat(gate.withMaintenance(() -> {
            assertThat(gate.isUpdating()).isTrue();
            assertThat(gate.isMaintenanceOwner()).isTrue();
            return "result";
        })).isEqualTo("result");
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void maintenanceExceptionPropagatesAndReleasesForAnotherThread() throws Exception {
        var failure = new IllegalStateException("operation failed");
        assertThatThrownBy(() -> gate.withMaintenance(() -> { throw failure; })).isSameAs(failure);
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void maintenanceErrorPropagatesAndReleasesForAnotherThread() throws Exception {
        var failure = new AssertionError("operation failed");
        assertThatThrownBy(() -> gate.withMaintenance(() -> { throw failure; })).isSameAs(failure);
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void nestedMaintenanceRefusalPreservesOuterOwnershipAndUpdating() throws Exception {
        AtomicInteger nestedCalls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            gate.withMaintenance(() -> {
                assertThatThrownBy(() -> gate.withMaintenance(nestedCalls::incrementAndGet))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Engine maintenance is unresolved");
                assertThat(nestedCalls.get()).isZero();
                assertThat(gate.isUpdating()).isTrue();
                assertThat(gate.isMaintenanceOwner()).isTrue();
                var probe = executor.submit(() -> {
                    assertThatThrownBy(() -> gate.apiCall("core", "action", "accessUrl", () -> "bad"))
                            .isInstanceOf(EngineBusyException.class);
                    return null;
                });
                assertDoesNotThrow(() -> probe.get(5, TimeUnit.SECONDS));
                return null;
            });
        }
        assertMaintenanceAndActionsAvailableFromAnotherThread();
    }

    @Test void quarantineRefusalDoesNotRunCallbackOrRetainLock() throws Exception {
        gate.withMaintenance(() -> { gate.markUpdatePending("pscanrules", "77.0.0"); return null; });
        AtomicInteger deniedCalls = new AtomicInteger();
        assertThatThrownBy(() -> gate.withMaintenance(deniedCalls::incrementAndGet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Engine maintenance is unresolved");
        assertThat(gate.isUpdating()).isFalse();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var probe = executor.submit(() -> {
                assertThatThrownBy(() -> gate.withMaintenance(deniedCalls::incrementAndGet))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Engine maintenance is unresolved");
                assertThatThrownBy(() -> gate.apiCall("core", "action", "accessUrl", () -> "bad"))
                        .isInstanceOf(ClientApiException.class)
                        .hasMessageContaining("quarantined");
                return null;
            });
            probe.get(5, TimeUnit.SECONDS);
        }
        assertThat(deniedCalls.get()).isZero();
        assertThat(gate.isQuarantined()).isTrue();
        assertThat(gate.isUpdating()).isFalse();
        assertThat(gate.isMaintenanceOwner()).isFalse();
    }

    private void assertMaintenanceAndActionsAvailableFromAnotherThread() throws Exception {
        assertThat(gate.isUpdating()).isFalse();
        assertThat(gate.isMaintenanceOwner()).isFalse();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(executor.submit(() -> gate.withMaintenance(() -> {
                assertThat(gate.isUpdating()).isTrue();
                assertThat(gate.isMaintenanceOwner()).isTrue();
                return "maintenance";
            })).get(5, TimeUnit.SECONDS)).isEqualTo("maintenance");
            assertThat(executor.submit(() -> gate.apiCall("core", "action", "accessUrl", () -> "OK"))
                    .get(5, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        assertThat(gate.isUpdating()).isFalse();
    }

    private void assertQuarantineAndLockReleaseFromAnotherThread(EngineMaintenanceGate quarantinedGate) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                assertThatThrownBy(() -> quarantinedGate.withMaintenance(() -> "bad"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("maintenance is unresolved");
                assertThatThrownBy(() -> quarantinedGate.apiCall("core", "action", "accessUrl", () -> "bad"))
                        .isInstanceOf(ClientApiException.class).hasMessageContaining("quarantined");
                assertThat(quarantinedGate.apiCall("core", "view", "version", () -> "visible")).isEqualTo("visible");
                return null;
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(quarantinedGate.isUpdating()).isFalse();
        assertThat(quarantinedGate.isMaintenanceOwner()).isFalse();
    }
}
