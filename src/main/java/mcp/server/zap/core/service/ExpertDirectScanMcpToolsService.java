package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for direct scan tools and active-scan policy controls.
 */
@Service
public class ExpertDirectScanMcpToolsService implements ExpertToolGroup {
    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final AjaxSpiderService ajaxSpiderService;

    public ExpertDirectScanMcpToolsService(ActiveScanService activeScanService,
                                           SpiderScanService spiderScanService,
                                           AjaxSpiderService ajaxSpiderService) {
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
    }

    @Tool(
            name = "zap_scan_policies_list",
            description = "List available ZAP active-scan policy names before using the policy parameter on direct or queued active scans."
    )
    public String listScanPolicies() {
        return activeScanService.listScanPolicies();
    }

    @Tool(
            name = "zap_scan_policy_view",
            description = "Inspect a named ZAP active-scan policy, including category defaults and scanner-rule IDs."
    )
    public String viewScanPolicy(
            @ToolParam(description = "Exact active-scan policy name from zap_scan_policies_list") String scanPolicyName,
            @ToolParam(required = false, description = "Optional exact rule ID or case-insensitive name fragment to narrow the rule list") String ruleFilter,
            @ToolParam(required = false, description = "Optional max number of rules to display (default: 25, max: 100)") String limit
    ) {
        return activeScanService.viewScanPolicy(scanPolicyName, ruleFilter, limit);
    }

    @Tool(
            name = "zap_scan_policy_rule_set",
            description = "Enable, disable, or tune specific scanner rules inside a named ZAP active-scan policy."
    )
    public String setScanPolicyRuleState(
            @ToolParam(description = "Exact active-scan policy name from zap_scan_policies_list") String scanPolicyName,
            @ToolParam(description = "Comma, whitespace, or newline separated numeric scanner rule IDs to change") String ruleIds,
            @ToolParam(required = false, description = "Optional true to enable or false to disable the listed rules") String enabled,
            @ToolParam(required = false, description = "Optional attack strength override") String attackStrength,
            @ToolParam(required = false, description = "Optional alert threshold override") String alertThreshold
    ) {
        return activeScanService.setScanPolicyRuleState(scanPolicyName, ruleIds, enabled, attackStrength, alertThreshold);
    }

    @Tool(
            name = "zap_active_scan_start",
            description = "Start a direct active scan for simple or single-replica workflows."
    )
    public String startActiveScan(
            @ToolParam(description = "Target URL to active scan") String targetUrl,
            @ToolParam(required = false, description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(required = false, description = "Scan policy name (optional)") String policy
    ) {
        return activeScanService.startActiveScan(targetUrl, recurse, policy);
    }

    @Tool(
            name = "zap_active_scan_as_user",
            description = "Start a direct authenticated active scan as a configured ZAP user."
    )
    public String startActiveScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to active scan") String targetUrl,
            @ToolParam(required = false, description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(required = false, description = "Scan policy name (optional)") String policy
    ) {
        return activeScanService.startActiveScanAsUser(contextId, userId, targetUrl, recurse, policy);
    }

    @Tool(
            name = "zap_active_scan_status",
            description = "Get progress for a direct active scan by ZAP scan ID."
    )
    public String getActiveScanStatus(
            @ToolParam(description = "ZAP active scan ID returned by zap_active_scan_start or zap_active_scan_as_user") String scanId
    ) {
        return activeScanService.getActiveScanStatus(scanId);
    }

    @Tool(
            name = "zap_active_scan_stop",
            description = "Stop a running direct active scan by ZAP scan ID."
    )
    public String stopActiveScan(
            @ToolParam(description = "ZAP active scan ID returned by zap_active_scan_start or zap_active_scan_as_user") String scanId
    ) {
        return activeScanService.stopActiveScan(scanId);
    }

    @Tool(
            name = "zap_spider_start",
            description = "Start a direct spider scan for simple or single-replica workflows."
    )
    public String startSpiderScan(
            @ToolParam(description = "Target URL to spider") String targetUrl
    ) {
        return spiderScanService.startSpiderScan(targetUrl);
    }

    @Tool(
            name = "zap_spider_as_user",
            description = "Start a direct authenticated spider scan as a configured ZAP user."
    )
    public String startSpiderScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to spider") String targetUrl,
            @ToolParam(required = false, description = "Maximum children to crawl (optional)") String maxChildren,
            @ToolParam(required = false, description = "Recurse into sub-paths? true/false (optional, default: true)") String recurse,
            @ToolParam(required = false, description = "Restrict to subtree only? true/false (optional, default: false)") String subtreeOnly
    ) {
        return spiderScanService.startSpiderScanAsUser(contextId, userId, targetUrl, maxChildren, recurse, subtreeOnly);
    }

    @Tool(
            name = "zap_spider_status",
            description = "Get progress for a direct spider scan by ZAP scan ID."
    )
    public String getSpiderScanStatus(
            @ToolParam(description = "ZAP spider scan ID returned by zap_spider_start or zap_spider_as_user") String scanId
    ) {
        return spiderScanService.getSpiderScanStatus(scanId);
    }

    @Tool(
            name = "zap_spider_stop",
            description = "Stop a running direct spider scan by ZAP scan ID."
    )
    public String stopSpiderScan(
            @ToolParam(description = "ZAP spider scan ID returned by zap_spider_start or zap_spider_as_user") String scanId
    ) {
        return spiderScanService.stopSpiderScan(scanId);
    }

    @Tool(
            name = "zap_ajax_spider",
            description = "Start an AJAX Spider scan using a real browser."
    )
    public String startAjaxSpider(
            @ToolParam(description = "Target URL to AJAX spider scan (e.g., http://example.com)") String targetUrl
    ) {
        return ajaxSpiderService.startAjaxSpider(targetUrl);
    }

    @Tool(name = "zap_ajax_spider_status", description = "Get the current status of the AJAX Spider scan")
    public String getAjaxSpiderStatus() {
        return ajaxSpiderService.getAjaxSpiderStatus();
    }

    @Tool(name = "zap_ajax_spider_stop", description = "Stop the currently running AJAX Spider scan")
    public String stopAjaxSpider() {
        return ajaxSpiderService.stopAjaxSpider();
    }

    @Tool(name = "zap_ajax_spider_results", description = "Get full results from the AJAX Spider scan including all discovered URLs")
    public String getAjaxSpiderResults() {
        return ajaxSpiderService.getAjaxSpiderResults();
    }
}
