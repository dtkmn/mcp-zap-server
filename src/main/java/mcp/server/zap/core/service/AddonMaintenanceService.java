package mcp.server.zap.core.service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import mcp.server.zap.core.gateway.EngineMaintenanceGate;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Addon;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Inventory;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.PassiveRule;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import org.springframework.core.env.Environment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Inspect -> explicitly approve a current Marketplace candidate -> verify the loaded engine. */
@Service
public class AddonMaintenanceService {
    public static final String UPDATE_SCOPE = "zap:engine:addons:update";
    private static final Set<String> RESTART_STATUSES = Set.of("UNINSTALLATION_FAILED", "SOFT_UNINSTALLATION_FAILED");
    private final ZapEngineAddonAccess engine;
    private final EngineMaintenanceGate gate;
    private final AddonMaintenanceProperties properties;
    private final ToolAuthorizationProperties authorization;
    private final Environment environment;
    private volatile UpdateResult lastUpdate;

    public AddonMaintenanceService(ZapEngineAddonAccess engine, EngineMaintenanceGate gate,
                                   AddonMaintenanceProperties properties, ToolAuthorizationProperties authorization,
                                   Environment environment) {
        this.engine = engine;
        this.gate = gate;
        this.properties = properties;
        this.authorization = authorization;
        this.environment = environment;
    }

    public record Inspection(String status, String observedAt, String addonId, String engineVersion,
                             Addon installedAddon, Addon localAddon, Addon marketplaceCandidate,
                             List<PassiveRule> passiveRules, String passiveExecutionState, boolean updatesEnabled, boolean quarantined,
                             String message) { }
    public record AddonChange(String id, Addon before, Addon after) { }
    public record UpdateResult(String status, String message, String addonId, List<Addon> before,
                               List<Addon> after, List<AddonChange> inventoryChanges,
                               Inspection verification, boolean quarantined) { }
    public record UpdateStatus(Inspection verification, UpdateResult lastUpdate) { }

    public Inspection inspect(String addonId, List<String> passiveRuleIds) {
        validateId(addonId);
        List<String> rules = validateRules(passiveRuleIds);
        Inventory inventory;
        List<Addon> marketplace;
        try { inventory = engine.inventory(); }
        catch (Exception e) { return unknown(addonId, "Loaded add-on inventory is unavailable; readiness is unknown."); }
        try { marketplace = engine.marketplace(); }
        catch (Exception e) { marketplace = List.of(); }
        return inspection(addonId, null, rules, inventory, marketplace);
    }

    public UpdateStatus status(String addonId, String expectedVersion, List<String> passiveRuleIds) {
        validateId(addonId);
        validateVersion(expectedVersion);
        List<String> rules = validateRules(passiveRuleIds);
        Inspection inspection;
        try { inspection = inspection(addonId, expectedVersion, rules, engine.inventory(), List.of()); }
        catch (Exception e) { inspection = unknown(addonId, "Loaded add-on inventory is unavailable; readiness is unknown."); }
        UpdateResult last = lastUpdate;
        return new UpdateStatus(inspection, last != null && last.addonId().equals(addonId) ? last : null);
    }

    public UpdateResult update(String addonId, String expectedInstalledVersion, String expectedCandidateVersion,
                               Boolean acknowledgeDependencyChanges, List<String> passiveRuleIds) {
        validateId(addonId);
        validateVersion(expectedInstalledVersion);
        validateVersion(expectedCandidateVersion);
        List<String> rules = validateRules(passiveRuleIds);
        requireAuthority();
        if (!properties.getAllowedAddonIds().contains(addonId)) throw new AccessDeniedException("This installed add-on is not approved for updates.");
        if (!Boolean.TRUE.equals(acknowledgeDependencyChanges)) throw new IllegalArgumentException("Acknowledge that Marketplace installation can also add or update dependencies.");
        if (compareVersions(expectedCandidateVersion, expectedInstalledVersion) <= 0) throw new IllegalArgumentException("The approved candidate must be newer than the installed version.");

        try (var ignored = gate.beginMaintenance()) {
            Inventory before;
            List<Addon> marketplace;
            try {
                before = engine.inventory();
                marketplace = engine.marketplace();
                Addon installed = find(before.installed(), addonId);
                Addon local = find(before.local(), addonId);
                Addon candidate = find(marketplace, addonId);
                if (installed == null || local == null || !"INSTALLED".equals(installed.installationStatus())
                        || !"INSTALLED".equals(local.installationStatus()) || !expectedInstalledVersion.equals(installed.version())
                        || !expectedInstalledVersion.equals(local.version()) || candidate == null
                        || !expectedCandidateVersion.equals(candidate.version()) || !"AVAILABLE".equals(candidate.installationStatus())) {
                    throw new IllegalStateException("Installed or Marketplace version/status changed or is unavailable; inspect again and approve the current candidate.");
                }
                engine.requireQuiescent(gate, before);
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("Cannot verify inventory, Marketplace candidate and engine inactivity; no install request was sent."); }

            // HTTP timeout or cancellation cannot cancel ZAP's synchronous install. Persist first.
            gate.markUpdatePending(addonId, expectedCandidateVersion);
            try {
                engine.install(addonId);
                Inventory after = engine.inventory();
                Inspection verification = inspection(addonId, expectedCandidateVersion, rules, after, marketplace);
                List<AddonChange> changes = changes(before.installed(), after.installed());
                boolean loaded = loadedCandidate(addonId, expectedCandidateVersion, after)
                        && changedAddonsLoaded(before, after);
                if (!loaded || Set.of("unknown", "pending_restart").contains(verification.status())) {
                    String status = "pending_restart".equals(verification.status()) ? "pending_restart" : "unknown";
                    return remember(new UpdateResult(status,
                            "The install response did not establish loaded readiness. Engine actions remain quarantined. An operator must reconcile the engine and maintenance marker; no restart was performed.",
                            addonId, before.installed(), after.installed(), changes, verification, true));
                }
                gate.completeVerifiedUpdate();
                // Re-read quarantine flag after removing the marker; loaded measurements are unchanged.
                verification = new Inspection(verification.status(), verification.observedAt(), verification.addonId(),
                        verification.engineVersion(), verification.installedAddon(), verification.localAddon(),
                        verification.marketplaceCandidate(), verification.passiveRules(), verification.passiveExecutionState(), verification.updatesEnabled(),
                        false, verification.message());
                return remember(new UpdateResult(verification.status(),
                        "Installation completed and the resulting loaded inventory was verified. Inventory changes include dependency changes; Marketplace does not pin arbitrary versions. No restart was performed.",
                        addonId, before.installed(), after.installed(), changes, verification, false));
            } catch (Exception e) {
                return remember(new UpdateResult("unknown",
                        "Installation completion is unknown or verification failed. The engine may still be updating. Engine actions remain quarantined; do not retry. An operator must reconcile the engine and maintenance marker. No restart was performed.",
                        addonId, before.installed(), List.of(), List.of(), null, true));
            }
        }
    }

    private void requireAuthority() {
        if (!properties.isUpdatesEnabled()) throw new AccessDeniedException("Runtime add-on updates are disabled by the operator.");
        String mode = environment.getProperty("mcp.server.security.mode", "api-key");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String clientId = RequestIdentityHolder.currentClientId();
        boolean exactScope = authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority -> ("SCOPE_" + UPDATE_SCOPE).equals(authority.getAuthority()));
        if (!environment.getProperty("mcp.server.security.enabled", Boolean.class, true)
                || !(mode.equalsIgnoreCase("api-key") || mode.equalsIgnoreCase("jwt"))
                || authorization.getMode() != ToolAuthorizationProperties.Mode.ENFORCE
                || !exactScope || clientId == null || !clientId.equals(authentication.getName())
                || !properties.getOperatorClientIds().contains(clientId)) {
            throw new AccessDeniedException("Add-on updates require enforced authentication, an exact maintenance scope and an approved operator client identity; wildcard grants do not authorize updates.");
        }
        if (!properties.isExclusiveEngine() || properties.getControllerReplicas() != 1
                || !"single-node".equals(normalizedBackend("zap.scan.queue.coordinator.backend", "single-node"))
                || !"in-memory".equals(normalizedBackend("zap.scan.jobs.store.backend", "in-memory"))) {
            throw new AccessDeniedException("Add-on updates support only an operator-declared exclusive engine with one controller and single-node coordination.");
        }
    }

    private String normalizedBackend(String property, String fallback) {
        return environment.getProperty(property, fallback).trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Inspection inspection(String addonId, String expectedVersion, List<String> requestedRules,
                                  Inventory inventory, List<Addon> marketplace) {
        Addon installed = find(inventory.installed(), addonId);
        Addon local = find(inventory.local(), addonId);
        Addon candidate = find(marketplace, addonId);
        List<PassiveRule> rules = List.of();
        String state = "configured";
        String message = "Loaded add-on and requested passive rule states were measured. Global passive execution enabled state has no ZAP view and remains unknown. This is configuration evidence, not detection or exploitability evidence; Marketplace metadata is cached by ZAP.";
        if (installed == null) state = "unavailable";
        if (local != null && RESTART_STATUSES.contains(local.installationStatus())) state = "pending_restart";
        else if (installed != null && (local == null || !"INSTALLED".equals(installed.installationStatus())
                || !"INSTALLED".equals(local.installationStatus()) || !installed.version().equals(local.version())
                || (expectedVersion != null && !expectedVersion.equals(installed.version())))) state = "unknown";
        if (!requestedRules.isEmpty()) {
            try {
                List<PassiveRule> allRules = engine.passiveRules();
                rules = allRules.stream().filter(rule -> requestedRules.contains(rule.id())).toList();
                if (state.equals("configured")) {
                    if (rules.size() != requestedRules.size()) state = "unavailable";
                    else if (rules.stream().anyMatch(rule -> !Boolean.parseBoolean(rule.enabled()) || "OFF".equalsIgnoreCase(rule.alertThreshold()))) state = "disabled";
                }
            } catch (Exception e) { state = "unknown"; }
        }
        if (gate.isQuarantined() && !gate.isUpdating()) {
            state = "unknown";
            message = "Unresolved maintenance is quarantining engine actions. Read-only measurements cannot prove that a timed-out install has stopped. Operator reconciliation is required.";
        } else if (gate.isUpdating() && !gate.isMaintenanceOwner()) {
            state = "unknown";
            message = "Engine maintenance is in progress; loaded observations are provisional.";
        }
        return new Inspection(state, Instant.now().toString(), addonId, inventory.engineVersion(), installed, local,
                candidate, rules, "unknown", properties.isUpdatesEnabled(), gate.isQuarantined(), message);
    }

    private boolean loadedCandidate(String id, String version, Inventory inventory) {
        Addon installed = find(inventory.installed(), id), local = find(inventory.local(), id);
        return installed != null && local != null && version.equals(installed.version()) && version.equals(local.version())
                && "INSTALLED".equals(installed.installationStatus()) && "INSTALLED".equals(local.installationStatus());
    }

    private boolean changedAddonsLoaded(Inventory before, Inventory after) {
        for (Addon old : before.installed()) if (find(after.installed(), old.id()) == null) return false;
        for (Addon loaded : after.installed()) if (!loadedCandidate(loaded.id(), loaded.version(), after)) return false;
        for (Addon addon : after.local()) {
            Addon old = find(before.local(), addon.id());
            if (!Objects.equals(old, addon) && !loadedCandidate(addon.id(), addon.version(), after)) return false;
        }
        return true;
    }

    private List<AddonChange> changes(List<Addon> before, List<Addon> after) {
        Map<String, Addon> previous = new LinkedHashMap<>(), current = new LinkedHashMap<>();
        before.forEach(addon -> previous.put(addon.id(), addon));
        after.forEach(addon -> current.put(addon.id(), addon));
        Set<String> ids = new java.util.TreeSet<>(previous.keySet());
        ids.addAll(current.keySet());
        List<AddonChange> result = new ArrayList<>();
        for (String id : ids) if (!Objects.equals(previous.get(id), current.get(id))) result.add(new AddonChange(id, previous.get(id), current.get(id)));
        return List.copyOf(result);
    }

    private UpdateResult remember(UpdateResult result) { lastUpdate = result; return result; }
    private Addon find(List<Addon> addons, String id) { return addons.stream().filter(addon -> addon.id().equals(id)).findFirst().orElse(null); }
    private Inspection unknown(String id, String message) { return new Inspection("unknown", Instant.now().toString(), id, null, null, null, null, List.of(), "unknown", properties.isUpdatesEnabled(), gate.isQuarantined(), message); }
    private void validateId(String id) { if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Supply one Marketplace add-on ID, not a URL, file or list."); }
    private void validateVersion(String version) { if (version == null || version.length() > 64 || !version.matches("[0-9]+(?:\\.[0-9]+){0,3}")) throw new IllegalArgumentException("Copy the exact numeric version from add-on inspection."); }
    private List<String> validateRules(List<String> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 20 || ids.stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,10}")) || ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("Supply up to 20 distinct numeric passive rule IDs as strings.");
        return List.copyOf(ids);
    }
    private int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int comparison = new BigInteger(i < a.length ? a[i] : "0").compareTo(new BigInteger(i < b.length ? b[i] : "0"));
            if (comparison != 0) return comparison;
        }
        return 0;
    }
}
