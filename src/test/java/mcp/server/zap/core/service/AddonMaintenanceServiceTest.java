package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import mcp.server.zap.core.gateway.EngineMaintenanceGate;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Addon;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Inventory;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.PassiveRule;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.zaproxy.clientapi.core.ClientApiException;

class AddonMaintenanceServiceTest {
    @TempDir Path directory;
    AddonMaintenanceProperties properties;
    ToolAuthorizationProperties authorization;
    MockEnvironment environment;
    ZapEngineAddonAccess engine;
    EngineMaintenanceGate gate;
    AddonMaintenanceService service;
    Inventory before;
    Inventory after;

    @BeforeEach void setup() throws Exception {
        properties = new AddonMaintenanceProperties();
        properties.setStateDirectory(directory.toString());
        properties.setUpdatesEnabled(true);
        properties.setExclusiveEngine(true);
        properties.setOperatorClientIds(List.of("operator"));
        authorization = new ToolAuthorizationProperties();
        environment = new MockEnvironment();
        engine = mock(ZapEngineAddonAccess.class);
        gate = new EngineMaintenanceGate(properties);
        service = new AddonMaintenanceService(engine, gate, properties, authorization, environment);
        before = inventory(addon("pscanrules", "76.0.0", "INSTALLED"));
        after = inventory(addon("pscanrules", "77.0.0", "INSTALLED"));
        when(engine.inventory()).thenReturn(before, after);
        when(engine.marketplace()).thenReturn(List.of(addon("pscanrules", "77.0.0", "AVAILABLE")));
        when(engine.passiveRules()).thenReturn(List.of(new PassiveRule("20020", "true", "MEDIUM")));
        authenticate("operator", AddonMaintenanceService.UPDATE_SCOPE);
    }

    @AfterEach void clearIdentity() { RequestIdentityHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test void inspectionNeedsNoScanAndPreservesExactVersions() {
        properties.setUpdatesEnabled(false);
        SecurityContextHolder.clearContext();
        var result = service.inspect("pscanrules", List.of("20020"));
        assertThat(result.status()).isEqualTo("configured");
        assertThat(result.engineVersion()).isEqualTo("2.17.0");
        assertThat(result.installedAddon().version()).isEqualTo("76.0.0");
        assertThat(result.marketplaceCandidate().version()).isEqualTo("77.0.0");
        assertThat(result.passiveRules()).extracting(PassiveRule::id).containsExactly("20020");
    }

    @Test void disabledByDefaultCausesNoEngineCalls() {
        assertThat(new AddonMaintenanceProperties().isUpdatesEnabled()).isFalse();
        properties.setUpdatesEnabled(false);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @ValueSource(strings = {"*", "zap:scan:active:run", "zap:engine:addons:read", "zap:engine:*"})
    void wildcardAndScanningPermissionsCannotUpdate(String scope) {
        authenticate("operator", scope);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @Test void exactScopeStillNeedsApprovedIdentityAndAuthenticatedContext() {
        authenticate("scanner", AddonMaintenanceService.UPDATE_SCOPE);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        authenticate("operator", AddonMaintenanceService.UPDATE_SCOPE);
        RequestIdentityHolder.setClientId("someone-else");
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        RequestIdentityHolder.clear();
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "disabled", "unknown"})
    void unauthenticatedModesCannotEnableMutation(String mode) {
        environment.setProperty("mcp.server.security.mode", mode);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @Test void disabledSecurityAndWarnOrOffAuthorizationCannotEnableMutation() {
        environment.setProperty("mcp.server.security.enabled", "false");
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        environment.setProperty("mcp.server.security.enabled", "true");
        for (var mode : List.of(ToolAuthorizationProperties.Mode.WARN, ToolAuthorizationProperties.Mode.OFF)) {
            authorization.setMode(mode);
            assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(engine);
    }

    @Test void sharedOrDistributedTopologiesRefuseBeforeEngineCalls() {
        properties.setExclusiveEngine(false);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        properties.setExclusiveEngine(true);
        properties.setControllerReplicas(2);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        properties.setControllerReplicas(1);
        environment.setProperty("zap.scan.queue.coordinator.backend", "postgres");
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        environment.setProperty("zap.scan.queue.coordinator.backend", "single-node");
        environment.setProperty("zap.scan.jobs.store.backend", "postgres");
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @ValueSource(strings = {"POSTGRES", " postgres ", "unknown", "", "file", "in_memory"})
    void onlyNormalizedInMemoryJobStoreCanAuthorizeUpdates(String backend) {
        environment.setProperty("zap.scan.jobs.store.backend", backend);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @ValueSource(strings = {"POSTGRES", " postgres ", "unknown", "", "single_node"})
    void unknownOrDistributedCoordinatorBackendCannotAuthorizeUpdates(String backend) {
        environment.setProperty("zap.scan.queue.coordinator.backend", backend);
        assertThatThrownBy(this::update).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @ValueSource(strings = {"in-memory", "IN-MEMORY", " in-memory "})
    void normalizedSupportedTopologyRetainsUpdateWorkflow(String backend) throws Exception {
        environment.setProperty("zap.scan.jobs.store.backend", backend);
        environment.setProperty("zap.scan.queue.coordinator.backend", " SINGLE-NODE ");
        assertThat(update().status()).isEqualTo("configured");
        verify(engine).install("pscanrules");
    }

    @Test void rejectsNewAddonsUnapprovedIdsAndMissingDependencyAcknowledgment() throws Exception {
        assertThatThrownBy(() -> service.update("other", "1", "2", true, List.of())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update("pscanrules", "76.0.0", "77.0.0", false, List.of())).isInstanceOf(IllegalArgumentException.class);
        when(engine.inventory()).thenReturn(new Inventory("2.17.0", List.of(), List.of()));
        assertThatThrownBy(this::update).isInstanceOf(IllegalStateException.class);
        verify(engine, never()).install(any());
    }

    @ParameterizedTest @ValueSource(strings = {"https://evil.test/a.zap", "../addon.zap", "pscanrules,other", "pscanrules\nother"})
    void arbitraryDestinationsCannotBecomeAddonInputs(String id) {
        assertThatThrownBy(() -> service.update(id, "76", "77", true, List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engine);
    }

    @Test void versionDriftAndDowngradeSendNoInstall() throws Exception {
        assertThatThrownBy(() -> service.update("pscanrules", "75.0.0", "77.0.0", true, List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update("pscanrules", "76.0.0", "78.0.0", true, List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.update("pscanrules", "77.0.0", "76.0.0", true, List.of())).isInstanceOf(IllegalArgumentException.class);
        verify(engine, never()).install(any());
        assertThat(Files.exists(directory.resolve("update.pending"))).isFalse();
    }

    @Test void busyOrUnknownActivityRefusesWithoutLeavingUpdateMarker() throws Exception {
        doThrow(new IllegalStateException("busy")).when(engine).requireQuiescent(any(), eq(before));
        assertThatThrownBy(this::update).isInstanceOf(IllegalStateException.class);
        verify(engine, never()).install(any());
        assertThat(gate.isQuarantined()).isFalse();
    }

    @Test void updatesThenVerifiesLoadedVersionRuleAndDependencyChanges() throws Exception {
        Addon dependency = addon("commonlib", "1.41.0", "INSTALLED");
        when(engine.inventory()).thenReturn(before, inventory(after.installed().getFirst(), dependency));
        var result = update();
        assertThat(result.status()).isEqualTo("configured");
        assertThat(result.quarantined()).isFalse();
        assertThat(result.verification().installedAddon().version()).isEqualTo("77.0.0");
        assertThat(result.inventoryChanges()).extracting(AddonMaintenanceService.AddonChange::id)
                .containsExactly("commonlib", "pscanrules");
        verify(engine).requireQuiescent(gate, before);
        verify(engine).install("pscanrules");
        assertThat(Files.exists(directory.resolve("update.pending"))).isFalse();
    }

    @Test void timeoutPersistsQuarantineAndNeverRetriesOrReportsReady() throws Exception {
        doThrow(new ClientApiException("simulated timeout with private request detail")).when(engine).install("pscanrules");
        var result = update();
        assertThat(result.status()).isEqualTo("unknown");
        assertThat(result.message()).doesNotContain("private request detail");
        assertThat(result.quarantined()).isTrue();
        assertThat(Files.readString(directory.resolve("update.pending"))).isEqualTo("pscanrules:77.0.0\n");
        assertThatThrownBy(this::update).isInstanceOf(IllegalStateException.class);
        assertThat(service.status("pscanrules", "77.0.0", List.of("20020")).verification().status()).isEqualTo("unknown");
        assertThat(new EngineMaintenanceGate(properties).isQuarantined()).isTrue();
        verify(engine).install("pscanrules");
    }

    @Test void failedDynamicUnloadIsPendingRestartWithoutAutomaticLifecycleAction() throws Exception {
        var local = addon("pscanrules", "77.0.0", "UNINSTALLATION_FAILED");
        when(engine.inventory()).thenReturn(before, new Inventory("2.17.0", before.installed(), List.of(local)));
        var result = update();
        assertThat(result.status()).isEqualTo("pending_restart");
        assertThat(result.quarantined()).isTrue();
        assertThat(result.message()).contains("no restart was performed");
    }

    @Test void unexpectedActualVersionOrMalformedRulesStayQuarantined() throws Exception {
        when(engine.inventory()).thenReturn(before, inventory(addon("pscanrules", "78.0.0", "INSTALLED")));
        assertThat(update().status()).isEqualTo("unknown");
        assertThat(gate.isQuarantined()).isTrue();
    }

    @Test void unknownPassiveRulesAreNotSuccessfulVerification() throws Exception {
        when(engine.passiveRules()).thenThrow(new IllegalStateException("malformed"));
        assertThat(update().status()).isEqualTo("unknown");
        assertThat(gate.isQuarantined()).isTrue();
    }

    @Test void disabledOrMissingRequestedRulesAreReportedHonestly() throws Exception {
        when(engine.passiveRules()).thenReturn(List.of(new PassiveRule("20020", "false", "OFF")));
        assertThat(update().status()).isEqualTo("disabled");
        assertThat(gate.isQuarantined()).isFalse();
        when(engine.inventory()).thenReturn(after);
        when(engine.passiveRules()).thenReturn(List.of());
        assertThat(service.status("pscanrules", "77.0.0", List.of("20020")).verification().status()).isEqualTo("unavailable");
    }

    private AddonMaintenanceService.UpdateResult update() { return service.update("pscanrules", "76.0.0", "77.0.0", true, List.of("20020")); }
    private static Addon addon(String id, String version, String installation) { return new Addon(id, version, "release", installation); }
    private static Inventory inventory(Addon... addons) { return new Inventory("2.17.0", List.of(addons), List.of(addons)); }
    private void authenticate(String id, String... scopes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id, null,
                java.util.Arrays.stream(scopes).map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList()));
        RequestIdentityHolder.set(id, "private-workspace");
    }
}
