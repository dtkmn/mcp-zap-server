package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Service for generating ZAP reports.
 * This service provides methods to view available report templates and generate reports in various formats.
 */
@Slf4j
@Service
public class ReportService {
    private static final int DEFAULT_REPORT_READ_MAX_CHARS = 20000;
    private static final int MAX_REPORT_READ_MAX_CHARS = 200000;

    private final ClientApi zap;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    /**
     * Build-time dependency injection constructor.
     */
    public ReportService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * List the available report templates.
     *
     * @return A string representation of the available report templates
     */
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
     * Generate a full session ZAP scan report using the supplied template.
     *
     * @param reportTemplate The report template to use (e.g. traditional-html-plus/traditional-json-plus)
     * @param theme         The report theme (dark/light)
     * @param sites         The sites to include in the report (comma-separated)
     * @return The path to the generated report file
     */
    public String generateReport(
            String reportTemplate,
            String theme,
            String sites
    ) {
        try {
            String normalizedTheme = normalizeTheme(reportTemplate, theme);
            ApiResponse raw = zap.reports.generate(
                    "My ZAP Scan Report",          // title
                    reportTemplate,                     // template ID
                    normalizedTheme,                    // theme
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

    private String normalizeTheme(String reportTemplate, String theme) {
        if (!templateSupportsTheme(reportTemplate)) {
            return "";
        }
        return theme == null ? "" : theme;
    }

    private boolean templateSupportsTheme(String reportTemplate) {
        if (reportTemplate == null) {
            return false;
        }
        String normalizedTemplate = reportTemplate.toLowerCase(Locale.ROOT);
        return !normalizedTemplate.contains("json")
                && !normalizedTemplate.contains("xml")
                && !normalizedTemplate.contains("sarif")
                && !normalizedTemplate.endsWith("-md");
    }

    public String readReport(
            String reportPath,
            Integer maxChars
    ) {
        String normalizedReportPath = requireText(reportPath, "reportPath");
        int boundedMaxChars = validateMaxChars(maxChars);
        Path reportRoot = Paths.get(reportDirectory).toAbsolutePath().normalize();
        Path resolvedPath = resolveReportPath(reportRoot, normalizedReportPath);

        if (!resolvedPath.startsWith(reportRoot)) {
            throw new IllegalArgumentException("Report path must stay within the configured report directory");
        }
        if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
            throw new IllegalArgumentException("Report file does not exist: " + resolvedPath);
        }

        try {
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            boolean truncated = content.length() > boundedMaxChars;
            String body = truncated ? content.substring(0, boundedMaxChars) : content;
            return new StringBuilder()
                    .append("Report artifact").append('\n')
                    .append("Path: ").append(resolvedPath).append('\n')
                    .append("Characters Returned: ").append(body.length()).append('\n')
                    .append("Truncated: ").append(truncated ? "yes" : "no").append('\n')
                    .append('\n')
                    .append(body)
                    .toString();
        } catch (IOException e) {
            log.error("Error reading report {}: {}", resolvedPath, e.getMessage(), e);
            throw new ZapApiException("Error reading generated report", e);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private int validateMaxChars(Integer maxChars) {
        int effectiveMaxChars = maxChars == null ? DEFAULT_REPORT_READ_MAX_CHARS : maxChars;
        if (effectiveMaxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than 0");
        }
        return Math.min(effectiveMaxChars, MAX_REPORT_READ_MAX_CHARS);
    }

    private Path resolveReportPath(Path reportRoot, String reportPath) {
        Path candidate = Paths.get(reportPath);
        if (candidate.isAbsolute()) {
            return candidate.toAbsolutePath().normalize();
        }
        return reportRoot.resolve(candidate).normalize();
    }

}
