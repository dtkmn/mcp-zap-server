package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;

import java.nio.file.Path;
import java.nio.file.Paths;

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
        } catch (Exception e) {
            log.error("Error getting report templates: {}", e.getMessage(), e);
            return "❌ Error getting report templates: " + e.getMessage();
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
    @Tool(name="zap_get_html_report",
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
        } catch (Exception e) {
            log.error("Error generating ZAP report: {}", e.getMessage(), e);
            return "❌ Error generating report: " + e.getMessage();
        }
    }

}
