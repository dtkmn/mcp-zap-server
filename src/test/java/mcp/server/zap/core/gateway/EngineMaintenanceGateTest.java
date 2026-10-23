package mcp.server.zap.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var action = executor.submit(() -> gate.apiCall("openapi", "action", "importUrl", () -> {
                running.countDown();
                try { assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new ClientApiException(e); }
                return "OK";
            }));
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(gate::beginMaintenance).isInstanceOf(IllegalStateException.class);
            } finally { finish.countDown(); }
            assertThat(action.get(5, TimeUnit.SECONDS)).isEqualTo("OK");
            try (var ignored = gate.beginMaintenance()) { assertThat(gate.isUpdating()).isTrue(); }
        }
    }

    @Test void maintenanceBlocksEveryActionFamilyButLeavesReadOnlyStatusAvailable() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var ignored = gate.beginMaintenance()) {
            var probe = executor.submit(() -> {
                for (String component : new String[]{"spider", "ascan", "ajaxSpider", "clientSpider", "automation", "openapi", "authentication", "core", "reports"}) {
                    assertThatThrownBy(() -> gate.apiCall(component, "action", "start", () -> requests.incrementAndGet()))
                            .isInstanceOf(EngineBusyException.class);
                }
                assertThat(gate.apiCall("pscan", "view", "scanners", () -> "visible")).isEqualTo("visible");
                return null;
            });
            probe.get(5, TimeUnit.SECONDS);
            assertThat(requests.get()).isZero();
        }
        assertThat(gate.apiCall("core", "action", "accessUrl", () -> "OK")).isEqualTo("OK");
    }

    @Test void unresolvedUpdateBlocksAfterRestartEvenWhenUpdatesAreDisabled() throws Exception {
        try (var ignored = gate.beginMaintenance()) { gate.markUpdatePending("pscanrules", "77.0.0"); }
        properties.setUpdatesEnabled(false);
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertThatThrownBy(() -> restored.apiCall("core", "action", "accessUrl", () -> "bad"))
                .isInstanceOf(ClientApiException.class);
        assertThat(restored.apiCall("autoupdate", "view", "installedAddons", () -> "visible")).isEqualTo("visible");
        assertThatThrownBy(restored::beginMaintenance).isInstanceOf(IllegalStateException.class);
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
        assertThatThrownBy(gate::beginMaintenance).isInstanceOf(IllegalStateException.class);
        EngineMaintenanceGate restored = new EngineMaintenanceGate(properties);
        assertThat(restored.isQuarantined()).isTrue();
        assertThatThrownBy(restored::beginMaintenance).isInstanceOf(IllegalStateException.class);
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
        try (var ignored = gate.beginMaintenance()) { assertThat(gate.isUpdating()).isTrue(); }
        assertThat(gate.apiCall("core", "action", "accessUrl", () -> "OK")).isEqualTo("OK");
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
        assertThatThrownBy(restored::beginMaintenance).isInstanceOf(IllegalStateException.class);
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
}
