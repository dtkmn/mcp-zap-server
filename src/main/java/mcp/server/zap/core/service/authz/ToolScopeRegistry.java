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
    public static final McpToolCapability DIRECT_SCAN_CAPABILITY = McpToolCapability.of("scan.direct");
    public static final McpToolCapability QUEUE_ADMISSION_CAPABILITY = McpToolCapability.of("queue.admission");
    public static final McpToolCapability AUTOMATION_EXECUTION_CAPABILITY = McpToolCapability.of("automation.execute");

    private final Map<String, List<String>> requiredScopesByTool;
    private final McpToolAccessRegistry toolAccessRegistry;

    public ToolScopeRegistry() {
        List<McpToolAccessRule> rules = new ArrayList<>();

        // Inventory and findings
        register(rules, "zap_alerts", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_alert_details", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_alert_instances", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_findings_snapshot", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_findings_diff", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_findings_summary", McpToolSurface.GUIDED, "zap:report:read");
        register(rules, "zap_findings_details", McpToolSurface.GUIDED, "zap:alerts:read");
        register(rules, "zap_hosts", McpToolSurface.EXPERT, "zap:inventory:read");
        register(rules, "zap_sites", McpToolSurface.EXPERT, "zap:inventory:read");
        register(rules, "zap_urls", McpToolSurface.EXPERT, "zap:inventory:read");

        // Guided execution
        register(rules, "zap_target_import", McpToolSurface.GUIDED, "zap:api:import");
        register(rules, "zap_crawl_start", McpToolSurface.GUIDED, List.of(GUIDED_SCAN_CAPABILITY), "zap:scan:crawl:run");
        register(rules, "zap_crawl_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_crawl_stop", McpToolSurface.GUIDED, "zap:scan:stop");
        register(rules, "zap_attack_start", McpToolSurface.GUIDED, List.of(GUIDED_SCAN_CAPABILITY), "zap:scan:attack:run");
        register(rules, "zap_attack_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_attack_stop", McpToolSurface.GUIDED, "zap:scan:stop");
        register(rules, "zap_auth_session_prepare", McpToolSurface.GUIDED, "zap:auth:session:write");
        register(rules, "zap_auth_session_validate", McpToolSurface.GUIDED, "zap:auth:test");
        register(rules, "zap_report_generate", McpToolSurface.GUIDED, "zap:report:generate");

        // Direct scan execution
        register(rules, "zap_active_scan_start", McpToolSurface.EXPERT, List.of(DIRECT_SCAN_CAPABILITY), "zap:scan:active:run");
        register(rules, "zap_active_scan_as_user", McpToolSurface.EXPERT, List.of(DIRECT_SCAN_CAPABILITY), "zap:scan:active:run");
        register(rules, "zap_active_scan_status", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_active_scan_stop", McpToolSurface.EXPERT, "zap:scan:stop");
        register(rules, "zap_spider_start", McpToolSurface.EXPERT, List.of(DIRECT_SCAN_CAPABILITY), "zap:scan:spider:run");
        register(rules, "zap_spider_as_user", McpToolSurface.EXPERT, List.of(DIRECT_SCAN_CAPABILITY), "zap:scan:spider:run");
        register(rules, "zap_spider_status", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_spider_stop", McpToolSurface.EXPERT, "zap:scan:stop");
        register(rules, "zap_ajax_spider", McpToolSurface.EXPERT, List.of(DIRECT_SCAN_CAPABILITY), "zap:scan:ajax:run");
        register(rules, "zap_ajax_spider_status", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_ajax_spider_results", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_ajax_spider_stop", McpToolSurface.EXPERT, "zap:scan:stop");
        register(rules, "zap_passive_scan_status", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_passive_scan_wait", McpToolSurface.GUIDED, "zap:scan:read");

        // Queue execution
        register(rules, "zap_queue_active_scan", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:active:run");
        register(rules, "zap_queue_active_scan_as_user", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:active:run");
        register(rules, "zap_queue_spider_scan", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:spider:run");
        register(rules, "zap_queue_spider_scan_as_user", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:spider:run");
        register(rules, "zap_queue_ajax_spider", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:ajax:run");
        register(rules, "zap_scan_job_status", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_scan_job_list", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_scan_job_cancel", McpToolSurface.EXPERT, "zap:scan:stop");
        register(rules, "zap_scan_job_retry", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:queue:write");
        register(rules, "zap_scan_job_dead_letter_list", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_scan_job_dead_letter_requeue", McpToolSurface.EXPERT, List.of(QUEUE_ADMISSION_CAPABILITY), "zap:scan:queue:write");
        register(rules, "zap_scan_history_list", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_get", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_scan_history_export", McpToolSurface.EXPERT, "zap:scan:read");
        register(rules, "zap_scan_history_release_evidence", McpToolSurface.GUIDED, "zap:scan:read");
        register(rules, "zap_scan_history_customer_handoff", McpToolSurface.GUIDED, "zap:scan:read");

        // Policy controls
        register(rules, "zap_policy_dry_run", McpToolSurface.EXPERT, "zap:policy:dry-run");
        register(rules, "zap_scan_policies_list", McpToolSurface.EXPERT, "zap:scan:policy:read");
        register(rules, "zap_scan_policy_view", McpToolSurface.EXPERT, "zap:scan:policy:read");
        register(rules, "zap_scan_policy_rule_set", McpToolSurface.EXPERT, "zap:scan:policy:write");

        // API imports
        register(rules, "zap_import_openapi_spec_url", McpToolSurface.EXPERT, "zap:api:import");
        register(rules, "zap_import_openapi_spec_file", McpToolSurface.EXPERT, "zap:api:import");
        register(rules, "zap_import_graphql_schema_url", McpToolSurface.EXPERT, "zap:api:import");
        register(rules, "zap_import_graphql_schema_file", McpToolSurface.EXPERT, "zap:api:import");
        register(rules, "zap_import_soap_wsdl_url", McpToolSurface.EXPERT, "zap:api:import");
        register(rules, "zap_import_soap_wsdl_file", McpToolSurface.EXPERT, "zap:api:import");

        // Reports and automation
        register(rules, "zap_view_templates", McpToolSurface.EXPERT, "zap:report:read");
        register(rules, "zap_generate_report", McpToolSurface.EXPERT, "zap:report:generate");
        register(rules, "zap_get_findings_summary", McpToolSurface.EXPERT, "zap:alerts:read");
        register(rules, "zap_report_read", McpToolSurface.GUIDED, "zap:report:read");
        register(rules, "zap_automation_plan_run", McpToolSurface.EXPERT, List.of(AUTOMATION_EXECUTION_CAPABILITY), "zap:automation:run");
        register(rules, "zap_automation_plan_status", McpToolSurface.EXPERT, "zap:automation:read");
        register(rules, "zap_automation_plan_artifacts", McpToolSurface.EXPERT, "zap:automation:read");

        // Context and user management
        register(rules, "zap_contexts_list", McpToolSurface.EXPERT, "zap:context:read");
        register(rules, "zap_context_upsert", McpToolSurface.EXPERT, "zap:context:write");
        register(rules, "zap_users_list", McpToolSurface.EXPERT, "zap:user:read");
        register(rules, "zap_user_upsert", McpToolSurface.EXPERT, "zap:user:write");
        register(rules, "zap_context_auth_configure", McpToolSurface.EXPERT, "zap:context:write");
        register(rules, "zap_auth_test_user", McpToolSurface.EXPERT, "zap:auth:test", "zap:user:read");

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
