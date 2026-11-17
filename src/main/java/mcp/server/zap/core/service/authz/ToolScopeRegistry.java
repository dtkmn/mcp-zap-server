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
    public static final McpToolCapability SCAN_ADMISSION_CAPABILITY = McpToolCapability.of("scan.guided");

    private static final McpToolSurface DEFAULT_SURFACE = McpToolSurface.of("default");

    private final Map<String, List<String>> requiredScopesByTool;
    private final McpToolAccessRegistry toolAccessRegistry;

    public ToolScopeRegistry() {
        List<McpToolAccessRule> rules = new ArrayList<>();

        // Inventory and findings
        register(rules, "zap_addon_inspect", "zap:engine:addons:read");
        register(rules, "zap_addon_update_status", "zap:engine:addons:read");
        register(rules, "zap_addon_update", "zap:engine:addons:update");
        register(rules, "zap_findings_snapshot", "zap:alerts:read");
        register(rules, "zap_findings_diff", "zap:alerts:read");
        register(rules, "zap_findings_summary", "zap:report:read");
        register(rules, "zap_findings_details", "zap:alerts:read");

        // Target import, crawling, active scans, and reports
        register(rules, "zap_target_import", "zap:api:import");
        register(rules, "zap_crawl_start", List.of(SCAN_ADMISSION_CAPABILITY), "zap:scan:crawl:run");
        register(rules, "zap_crawl_status", "zap:scan:read");
        register(rules, "zap_crawl_stop", "zap:scan:stop");
        register(rules, "zap_attack_start", List.of(SCAN_ADMISSION_CAPABILITY), "zap:scan:attack:run");
        register(rules, "zap_attack_status", "zap:scan:read");
        register(rules, "zap_attack_stop", "zap:scan:stop");
        register(rules, "zap_scan_policies_list", "zap:scan:policy:read");
        register(rules, "zap_scan_policy_view", "zap:scan:policy:read");
        register(rules, "zap_auth_session_prepare", "zap:auth:session:write");
        register(rules, "zap_auth_session_validate", "zap:auth:test");
        register(rules, "zap_report_generate", "zap:report:generate");

        // Passive analysis status
        register(rules, "zap_passive_scan_status", "zap:scan:read");
        register(rules, "zap_passive_scan_wait", "zap:scan:read");

        // Scan ownership and history
        register(rules, "zap_scan_history_list", "zap:scan:read");
        register(rules, "zap_scan_history_get", "zap:scan:read");
        register(rules, "zap_scan_history_export", "zap:scan:read");
        register(rules, "zap_scan_history_release_evidence", "zap:scan:read");
        register(rules, "zap_scan_history_customer_handoff", "zap:scan:read");

        // Report artifact reads
        register(rules, "zap_report_read", "zap:report:read");
        register(rules, "zap_report_read_chunk", "zap:report:read");

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
                          String... requiredScopes) {
        register(rules, toolName, List.of(), requiredScopes);
    }

    private void register(List<McpToolAccessRule> rules,
                          String toolName,
                          List<McpToolCapability> capabilities,
                          String... requiredScopes) {
        rules.add(McpToolAccessRule.builder(toolName, DEFAULT_SURFACE)
                .capabilities(capabilities)
                .requiredScopes(List.of(requiredScopes))
                .build());
    }
}
