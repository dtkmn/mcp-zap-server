package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for findings inspection and report generation tools.
 */
@Service
public class ExpertResultsMcpToolsService implements ExpertToolGroup {
    private final FindingsService findingsService;
    private final ReportService reportService;

    public ExpertResultsMcpToolsService(FindingsService findingsService,
                                        ReportService reportService) {
        this.findingsService = findingsService;
        this.reportService = reportService;
    }

    @Tool(
            name = "zap_alert_details",
            description = "Get grouped alert metadata for a required target baseUrl covered by scan evidence visible to the caller, optionally narrowed by plugin ID or alert name."
    )
    public String getAlertDetails(
            @ToolParam(description = "Required nonblank target base URL covered by scan evidence visible to the caller") String baseUrl,
            @ToolParam(required = false, description = "ZAP plugin ID to narrow to a single alert family (optional)") String pluginId,
            @ToolParam(required = false, description = "Alert name to narrow to a single alert family (optional)") String alertName
    ) {
        return findingsService.getAlertDetails(baseUrl, pluginId, alertName);
    }

    @Tool(
            name = "zap_alert_instances",
            description = "Get bounded alert instances for a required target baseUrl covered by scan evidence visible to the caller, optionally narrowed by plugin ID or alert name."
    )
    public String getAlertInstances(
            @ToolParam(description = "Required nonblank target base URL covered by scan evidence visible to the caller") String baseUrl,
            @ToolParam(required = false, description = "ZAP plugin ID to narrow to a single alert family (optional)") String pluginId,
            @ToolParam(required = false, description = "Alert name to narrow to a single alert family (optional)") String alertName,
            @ToolParam(required = false, description = "Maximum instances to return (optional, default: 20, max: 100)") Integer limit
    ) {
        return findingsService.getAlertInstances(baseUrl, pluginId, alertName, limit);
    }

    @Tool(
            name = "zap_findings_snapshot",
            description = "Export a normalized findings snapshot as JSON for a required target baseUrl covered by scan evidence visible to the caller, so CI or release workflows can save a stable baseline."
    )
    public String exportFindingsSnapshot(
            @ToolParam(description = "Required nonblank target base URL covered by scan evidence visible to the caller") String baseUrl
    ) {
        return findingsService.exportFindingsSnapshot(baseUrl);
    }

    @Tool(
            name = "zap_findings_diff",
            description = "Compare current findings for a required target baseUrl covered by scan evidence visible to the caller against a previously exported findings snapshot."
    )
    public String diffFindings(
            @ToolParam(description = "Required nonblank target base URL covered by scan evidence visible to the caller") String baseUrl,
            @ToolParam(description = "JSON findings snapshot returned earlier by zap_findings_snapshot") String baselineSnapshot,
            @ToolParam(description = "Maximum grouped result lines to render") Integer maxGroups
    ) {
        return findingsService.diffFindings(baseUrl, baselineSnapshot, maxGroups);
    }

    @Tool(name = "zap_view_templates", description = "List the available report templates")
    public String viewTemplates() {
        return reportService.viewTemplates();
    }

    @Tool(
            name = "zap_generate_report",
            description = "Generate a full session ZAP scan report using the supplied template and return the artifact path"
    )
    public String generateReport(
            @ToolParam(description = "The report template to use") String reportTemplate,
            @ToolParam(description = "The report theme (dark/light)") String theme,
            @ToolParam(description = "The sites to include in the report (comma separated)") String sites
    ) {
        return reportService.generateReport(reportTemplate, theme, sites);
    }

    @Tool(
            name = "zap_get_findings_summary",
            description = "Get a high-level markdown summary of scan findings for a required target baseUrl covered by scan evidence visible to the caller, grouped by risk and alert type."
    )
    public String getFindingsSummary(
            @ToolParam(description = "Required nonblank target base URL covered by scan evidence visible to the caller") String baseUrl
    ) {
        return findingsService.getFindingsSummary(baseUrl);
    }

}
