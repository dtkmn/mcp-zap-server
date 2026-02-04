package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for generating ZAP reports.
 * This service provides methods to view available report templates and generate reports in various formats.
 */
@Slf4j
@Service
public class ReportService {

    private final ClientApi zap;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    public ReportService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * List the available report templates.
     *
     * @return A string representation of the available report templates
     */
    @Tool(name="zap_view_templates",
    description="List the available report templates")
    public String viewTemplates() {
        try {
            ApiResponse raw = zap.reports.templates();
            if (!(raw instanceof ApiResponseList list)) {
                throw new IllegalStateException("Getting report templates failed: " + raw);
            }
            StringBuilder sb = new StringBuilder();
            for (ApiResponse item : list.getItems()) {
                sb.append(item.toString()).append("\n");
            }
            return sb.toString().trim();
        } catch (ClientApiException e) {
            log.error("Error getting report templates: {}", e.getMessage(), e);
            throw new ZapApiException("Error getting report templates: " + e.getMessage(), e);
        }
    }


    /**
     * Generate a full session ZAP scan report in HTML format.
     *
     * @param reportTemplate The report template to use (e.g. traditional-html-plus/traditional-json-plus)
     * @param theme         The report theme (dark/light)
     * @param sites         The sites to include in the report (comma-separated)
     * @return The path to the generated report file
     */
    @Tool(name="zap_generate_report",
            description="Generate the full session ZAP scan report in HTML format, return the path to the file")
    public String getHtmlReport(
            @ToolParam(description = "The report template to use (eg. modern/traditional-html-plus/traditional-json-plus)") String reportTemplate,
            @ToolParam(description = "The report theme (dark/light") String theme,
            @ToolParam(description = "The sites to include in the report (commas separated)") String sites
    ) {
        try {
            ApiResponse raw = zap.reports.generate(
                    "My ZAP Scan Report",          // title
                    reportTemplate,                     // template ID
                    theme,                              // theme
                    "",                                 // description
                    "",                                 // contexts
                    sites,                              // sites
                    "",                                 // sections
                    "",                                 // includedConfidences
                    "",                                 // includedRisks
                    "zap-report-" + System.currentTimeMillis(),
                    "",                            // reportFileNamePattern
                    reportDirectory,
                    "false"                        // display=false means “don’t pop open a browser”
            );
            if (!(raw instanceof ApiResponseElement)) {
                throw new IllegalStateException("Report generation failed: " + raw);
            }
            String fileName = ((ApiResponseElement) raw).getValue();
            Path reportPath = Paths.get(fileName);
            return reportPath.toString();
        } catch (ClientApiException e) {
            log.error("Error generating ZAP report: {}", e.getMessage(), e);
            throw new ZapApiException("Error generating ZAP report: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a token-optimized markdown summary of the scan findings.
     * This is designed for LLM consumption to avoid context window overflow.
     *
     * @param baseUrl The base URL to filter alerts (optional)
     * @return A Markdown formatted summary of risks and counts.
     */
    @Tool(name = "zap_get_findings_summary",
            description = "Get a high-level markdown summary of scan findings, grouped by risk and alert type. Use this instead of reading the full report.")
    public String getFindingsSummary(@ToolParam(description = "Base URL to filter (optional)") String baseUrl) {
        try {
            // 1. Fetch all alerts
            ApiResponseList resp = (ApiResponseList) zap.core.alerts(
                    baseUrl != null ? baseUrl : "", "0", "-1"
            );

            if (resp.getItems().isEmpty()) {
                return "✅ **Scan Complete**: No alerts found.";
            }

            // 2. Data Structures for Aggregation
            // Map<RiskLevel, Map<AlertName, Count>>
            Map<String, Map<String, Integer>> riskGroups = new HashMap<>();
            // Map<AlertName, Description> (to provide context only once per alert type)
            Map<String, String> alertDescriptions = new HashMap<>();

            int totalAlerts = 0;

            // 3. Process Alerts
            for (ApiResponse item : resp.getItems()) {
                ApiResponseSet set = (ApiResponseSet) item;
                String name = set.getStringValue("alert");
                String risk = set.getStringValue("risk"); // High, Medium, Low, Informational
                String desc = set.getStringValue("description");

                // Normalize Risk string just in case
                riskGroups.putIfAbsent(risk, new HashMap<>());
                Map<String, Integer> counts = riskGroups.get(risk);
                counts.put(name, counts.getOrDefault(name, 0) + 1);

                // Take only first line of desc to save tokens
                alertDescriptions.putIfAbsent(name, desc != null ? desc.split("\n")[0] : "No description");
                totalAlerts++;
            }

            // 4. Build Markdown Output
            StringBuilder sb = new StringBuilder();
            sb.append("# 🛡️ Scan Findings Summary\n\n");
            sb.append("**Target:** ").append(baseUrl != null ? baseUrl : "All Targets").append("\n");
            sb.append("**Total Alerts:** ").append(totalAlerts).append("\n\n");

            // Define Risk Order
            String[] riskOrder = {"High", "Medium", "Low", "Informational"};

            for (String riskLevel : riskOrder) {
                if (riskGroups.containsKey(riskLevel)) {
                    Map<String, Integer> alerts = riskGroups.get(riskLevel);
                    sb.append("## 🔴 ").append(riskLevel).append(" Risk\n");

                    for (Map.Entry<String, Integer> entry : alerts.entrySet()) {
                        String alertName = entry.getKey();
                        int count = entry.getValue();
                        String shortDesc = alertDescriptions.get(alertName);

                        sb.append("* **").append(alertName).append("** (").append(count).append(" instances)\n");
                        sb.append("  > ").append(shortDesc).append("\n");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();

        } catch (ClientApiException e) {
            log.error("Error generating findings summary: {}", e.getMessage(), e);
            throw new ZapApiException("Error generating findings summary", e);
        }
    }

}
