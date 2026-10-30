package mcp.server.zap.core.service.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.authz.McpToolAccessRegistry;
import mcp.gateway.core.authz.McpToolAccessRule;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.tool.McpToolCapability;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.tool.McpToolSurface;
import org.springframework.stereotype.Component;

/**
 * Authoritative tool-to-scope mapping for the MCP surface.
 */
@Component
public class ToolScopeRegistry {
    public static final String TOOLS_LIST_ACTION = McpToolAuthorizer.TOOLS_LIST_ACTION;
    public static final McpToolCapability GUIDED_SCAN_CAPABILITY = McpToolCapability.of("scan.guided");

    private final Map<String, List<String>> requiredScopesByTool;
    private final McpToolAccessRegistry toolAccessRegistry;

    public ToolScopeRegistry() {
        List<McpToolAccessRule> rules = new ArrayList<>();

        // Inventory and findings
        register(rules, "zap_addon_inspect", McpToolSurface.GUIDED, "zap:engine:addons:read");
        register(rules, "zap_addon_update_status", McpToolSurface.GUIDED, "zap:engine:addons:read");
        register(rules, "zap_addon_update", McpToolSurface.GUIDED, "zap:engine:addons:update");
        register(rules, "zap_findings_snapshot", McpToolSurface.GUIDED, "zap:alerts:read");
        register(rules, "zap_findings_diff", McpToolSurface.GUIDED, "zap:alerts:read");
        register(rules, "zap_findings_summary", McpToolSurface.GUIDED, "zap:report:read");
        register(rules, "zap_findings_details", McpToolSurface.GUIDED, "zap:alerts:read");

        // Guided execution
        register(rules, "zap_target_import", McpToolSurface.GUIDED, "zap:api:import");
        register(rules, "zap_crawl_start", McpToolSurface.GUIDED, List.of(GUIDED_SCAN_CAPABILITY), "zap:scan:crawl:run");
        register(rules, "zap_crawl_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_crawl_stop", McpToolSurface.GUIDED, "zap:scan:stop");
        register(rules, "zap_attack_start", McpToolSurface.GUIDED, List.of(GUIDED_SCAN_CAPABILITY), "zap:scan:attack:run");
        register(rules, "zap_attack_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_attack_stop", McpToolSurface.GUIDED, "zap:scan:stop");
        register(rules, "zap_scan_policies_list", McpToolSurface.GUIDED, "zap:scan:policy:read");
        register(rules, "zap_scan_policy_view", McpToolSurface.GUIDED, "zap:scan:policy:read");
        register(rules, "zap_auth_session_prepare", McpToolSurface.GUIDED, "zap:auth:session:write");
        register(rules, "zap_auth_session_validate", McpToolSurface.GUIDED, "zap:auth:test");
        register(rules, "zap_report_generate", McpToolSurface.GUIDED, "zap:report:generate");

        // Passive analysis status
        register(rules, "zap_passive_scan_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_passive_scan_wait", McpToolSurface.GUIDED, "zap:scan:read");

        // Scan ownership and history
        register(rules, "zap_scan_job_reconcile", McpToolSurface.GUIDED, "zap:scan:queue:reconcile");
        register(rules, "zap_scan_job_reconciliation_status", McpToolSurface.GUIDED, "zap:scan:queue:reconcile");
        register(rules, "zap_scan_history_list", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_get", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_export", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_release_evidence", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_customer_handoff", McpToolSurface.GUIDED, "zap:scan:read");

        // Report artifact reads
        register(rules, "zap_report_read", McpToolSurface.GUIDED, "zap:report:read");
        register(rules, "zap_report_read_chunk", McpToolSurface.GUIDED, "zap:report:read");

        this.toolAccessRegistry = McpToolAccessRegistry.of(rules);
        this.requiredScopesByTool = toolAccessRegistry.requiredScopesByTool();
    }

    public Map<String, List<String>> getRequiredScopesByTool() {
        return requiredScopesByTool;
    }

    public McpToolAccessRegistry getToolAccessRegistry() {
        return toolAccessRegistry;
    }

    public List<String> getRequiredScopes(String toolName) {
        return toolAccessRegistry.requiredScopes(toolName).orElse(null);
    }

    public McpToolRegistry getToolRegistry() {
        return toolAccessRegistry.toolRegistry();
    }

    public boolean hasCapability(String toolName, McpToolCapability capability) {
        return toolAccessRegistry.hasCapability(toolName, capability);
    }

    public Set<String> getToolNamesByCapability(McpToolCapability capability) {
        return toolAccessRegistry.namesWithCapability(capability);
    }

    public List<String> getToolsListRequiredScopes() {
        return List.of(TOOLS_LIST_ACTION);
    }

    private void register(List<McpToolAccessRule> rules,
                          String toolName,
                          McpToolSurface surface,
                          String... requiredScopes) {
        register(rules, toolName, surface, List.of(), requiredScopes);
    }

    private void register(List<McpToolAccessRule> rules,
                          String toolName,
                          McpToolSurface surface,
                          List<McpToolCapability> capabilities,
                          String... requiredScopes) {
        rules.add(McpToolAccessRule.builder(toolName, surface)
                .capabilities(capabilities)
                .requiredScopes(List.of(requiredScopes))
                .build());
    }
}
