package mcp.server.zap.core.service.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Authoritative tool-to-scope mapping for the MCP surface.
 */
@Component
public class ToolScopeRegistry {
    public static final String TOOLS_LIST_ACTION = "mcp:tools:list";

    private final Map<String, List<String>> requiredScopesByTool;

    public ToolScopeRegistry() {
        Map<String, List<String>> scopes = new LinkedHashMap<>();

        // Inventory and findings
        register(scopes, "zap_alerts", "zap:alerts:read");
        register(scopes, "zap_alert_details", "zap:alerts:read");
        register(scopes, "zap_alert_instances", "zap:alerts:read");
        register(scopes, "zap_findings_snapshot", "zap:alerts:read");
        register(scopes, "zap_findings_diff", "zap:alerts:read");
        register(scopes, "zap_findings_summary", "zap:report:read");
        register(scopes, "zap_findings_details", "zap:alerts:read");
        register(scopes, "zap_hosts", "zap:inventory:read");
        register(scopes, "zap_sites", "zap:inventory:read");
        register(scopes, "zap_urls", "zap:inventory:read");

        // Guided execution
        register(scopes, "zap_target_import", "zap:api:import");
        register(scopes, "zap_crawl_start", "zap:scan:crawl:run");
        register(scopes, "zap_crawl_status", "zap:scan:read");
        register(scopes, "zap_crawl_stop", "zap:scan:stop");
        register(scopes, "zap_attack_start", "zap:scan:attack:run");
        register(scopes, "zap_attack_status", "zap:scan:read");
        register(scopes, "zap_attack_stop", "zap:scan:stop");
        register(scopes, "zap_auth_session_prepare", "zap:auth:session:write");
        register(scopes, "zap_auth_session_validate", "zap:auth:test");
        register(scopes, "zap_report_generate", "zap:report:generate");

        // Direct scan execution
        register(scopes, "zap_active_scan_start", "zap:scan:active:run");
        register(scopes, "zap_active_scan_as_user", "zap:scan:active:run");
        register(scopes, "zap_active_scan_status", "zap:scan:read");
        register(scopes, "zap_active_scan_stop", "zap:scan:stop");
        register(scopes, "zap_spider_start", "zap:scan:spider:run");
        register(scopes, "zap_spider_as_user", "zap:scan:spider:run");
        register(scopes, "zap_spider_status", "zap:scan:read");
        register(scopes, "zap_spider_stop", "zap:scan:stop");
        register(scopes, "zap_ajax_spider", "zap:scan:ajax:run");
        register(scopes, "zap_ajax_spider_status", "zap:scan:read");
        register(scopes, "zap_ajax_spider_results", "zap:scan:read");
        register(scopes, "zap_ajax_spider_stop", "zap:scan:stop");
        register(scopes, "zap_passive_scan_status", "zap:scan:read");
        register(scopes, "zap_passive_scan_wait", "zap:scan:read");

        // Queue execution
        register(scopes, "zap_queue_active_scan", "zap:scan:active:run");
        register(scopes, "zap_queue_active_scan_as_user", "zap:scan:active:run");
        register(scopes, "zap_queue_spider_scan", "zap:scan:spider:run");
        register(scopes, "zap_queue_spider_scan_as_user", "zap:scan:spider:run");
        register(scopes, "zap_queue_ajax_spider", "zap:scan:ajax:run");
        register(scopes, "zap_scan_job_status", "zap:scan:read");
        register(scopes, "zap_scan_job_list", "zap:scan:read");
        register(scopes, "zap_scan_job_cancel", "zap:scan:stop");
        register(scopes, "zap_scan_job_retry", "zap:scan:queue:write");
        register(scopes, "zap_scan_job_dead_letter_list", "zap:scan:read");
        register(scopes, "zap_scan_job_dead_letter_requeue", "zap:scan:queue:write");
        register(scopes, "zap_scan_history_list", "zap:scan:read");
        register(scopes, "zap_scan_history_get", "zap:scan:read");
        register(scopes, "zap_scan_history_export", "zap:scan:read");
        register(scopes, "zap_scan_history_release_evidence", "zap:scan:read");
        register(scopes, "zap_scan_history_customer_handoff", "zap:scan:read");

        // Policy controls
        register(scopes, "zap_policy_dry_run", "zap:policy:dry-run");
        register(scopes, "zap_scan_policies_list", "zap:scan:policy:read");
        register(scopes, "zap_scan_policy_view", "zap:scan:policy:read");
        register(scopes, "zap_scan_policy_rule_set", "zap:scan:policy:write");

        // API imports
        register(scopes, "zap_import_openapi_spec_url", "zap:api:import");
        register(scopes, "zap_import_openapi_spec_file", "zap:api:import");
        register(scopes, "zap_import_graphql_schema_url", "zap:api:import");
        register(scopes, "zap_import_graphql_schema_file", "zap:api:import");
        register(scopes, "zap_import_soap_wsdl_url", "zap:api:import");
        register(scopes, "zap_import_soap_wsdl_file", "zap:api:import");

        // Reports and automation
        register(scopes, "zap_view_templates", "zap:report:read");
        register(scopes, "zap_generate_report", "zap:report:generate");
        register(scopes, "zap_get_findings_summary", "zap:alerts:read");
        register(scopes, "zap_report_read", "zap:report:read");
        register(scopes, "zap_automation_plan_run", "zap:automation:run");
        register(scopes, "zap_automation_plan_status", "zap:automation:read");
        register(scopes, "zap_automation_plan_artifacts", "zap:automation:read");

        // Context and user management
        register(scopes, "zap_contexts_list", "zap:context:read");
        register(scopes, "zap_context_upsert", "zap:context:write");
        register(scopes, "zap_users_list", "zap:user:read");
        register(scopes, "zap_user_upsert", "zap:user:write");
        register(scopes, "zap_context_auth_configure", "zap:context:write");
        register(scopes, "zap_auth_test_user", "zap:auth:test", "zap:user:read");

        this.requiredScopesByTool = Collections.unmodifiableMap(scopes);
    }

    public Map<String, List<String>> getRequiredScopesByTool() {
        return requiredScopesByTool;
    }

    public List<String> getRequiredScopes(String toolName) {
        return requiredScopesByTool.get(toolName);
    }

    public List<String> getToolsListRequiredScopes() {
        return List.of(TOOLS_LIST_ACTION);
    }

    private void register(Map<String, List<String>> scopes, String toolName, String... requiredScopes) {
        scopes.put(toolName, List.of(requiredScopes));
    }
}
