package mcp.server.zap.core.service;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Guided add-on tools; no scan target or prior scan is required. */
@Service
public class AddonMaintenanceMcpToolsService {
    private final AddonMaintenanceService service;
    public AddonMaintenanceMcpToolsService(AddonMaintenanceService service) { this.service = service; }

    @Tool(name = "zap_addon_inspect", description = "Inspect loaded ZAP engine/add-on versions, cached Marketplace candidate and optional passive rule enabled states before scanning. Read-only; no prior target scan is needed.")
    public AddonMaintenanceService.Inspection inspect(
            @ToolParam(description = "One Marketplace add-on ID, for example pscanrules") String addonId,
            @ToolParam(required = false, description = "Up to 20 passive rule IDs as strings, for example [\"20020\"] (optional)") List<String> passiveRuleIds) {
        return service.inspect(addonId, passiveRuleIds);
    }

    @Tool(name = "zap_addon_update", description = "Operator-only update of one approved installed add-on through ZAP Marketplace. Disabled by default. Requires exact inspected current/candidate versions and explicit dependency-change acknowledgment. Refuses busy/shared engines. Verifies actual loaded results; ambiguous completion quarantines engine actions. Never restarts ZAP.")
    public AddonMaintenanceService.UpdateResult update(
            @ToolParam(description = "One approved, already installed Marketplace add-on ID") String addonId,
            @ToolParam(description = "Exact installed version returned by zap_addon_inspect") String expectedInstalledVersion,
            @ToolParam(description = "Exact cached Marketplace candidate version returned by zap_addon_inspect; ZAP chooses its candidate and cannot pin arbitrary versions") String expectedCandidateVersion,
            @ToolParam(description = "Must explicitly be true: Marketplace may also add/update dependencies outside the target-ID allowlist") Boolean acknowledgeDependencyChanges,
            @ToolParam(required = false, description = "Up to 20 passive rule IDs as strings to verify after installation (optional)") List<String> passiveRuleIds) {
        return service.update(addonId, expectedInstalledVersion, expectedCandidateVersion, acknowledgeDependencyChanges, passiveRuleIds);
    }

    @Tool(name = "zap_addon_update_status", description = "Read fresh loaded add-on and requested passive-rule readiness against an exact expected version, with the last local update result when available. Does not retry installs, clear quarantine or restart ZAP.")
    public AddonMaintenanceService.UpdateStatus status(
            @ToolParam(description = "One Marketplace add-on ID") String addonId,
            @ToolParam(description = "Exact expected loaded version copied from inspection or the approved update") String expectedVersion,
            @ToolParam(required = false, description = "Up to 20 passive rule IDs as strings (optional)") List<String> passiveRuleIds) {
        return service.status(addonId, expectedVersion, passiveRuleIds);
    }
}
