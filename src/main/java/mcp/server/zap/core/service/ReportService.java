package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.ReportArtifactBoundary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for generating ZAP reports.
 * This service provides methods to view available report templates and generate reports in various formats.
 */
@Slf4j
@Service
public class ReportService {
    private static final int DEFAULT_REPORT_READ_MAX_CHARS = 20000;
    private static final int MAX_REPORT_READ_MAX_CHARS = 200000;
    private static final Pattern SAFE_WORKSPACE_SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$");

    private final EngineReportAccess engineReportAccess;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ReportArtifactBoundary reportArtifactBoundary;
    private ScanHistoryLedgerService scanHistoryLedgerService;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    /**
     * Build-time dependency injection constructor.
     */
    public ReportService(EngineReportAccess engineReportAccess) {
        this.engineReportAccess = engineReportAccess;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setReportArtifactBoundary(ReportArtifactBoundary reportArtifactBoundary) {
        this.reportArtifactBoundary = reportArtifactBoundary;
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    /**
     * List the available report templates.
     *
     * @return A string representation of the available report templates
     */
    public String viewTemplates() {
        StringBuilder sb = new StringBuilder();
        for (String template : engineReportAccess.listReportTemplates()) {
            sb.append(template).append("\n");
        }
        return sb.toString().trim();
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
            Path configuredReportRoot = Paths.get(reportDirectory).toAbsolutePath().normalize();
            Path effectiveReportRoot = resolveWriteReportDirectory(configuredReportRoot);
            Files.createDirectories(effectiveReportRoot);
            String normalizedTheme = normalizeTheme(reportTemplate, theme);
            String fileName = engineReportAccess.generateReport(new ReportGenerationRequest(
                    "My ZAP Scan Report",
                    reportTemplate,
                    normalizedTheme,
                    "",
                    "",
                    sites,
                    "",
                    "",
                    "",
                    "zap-report-" + System.currentTimeMillis(),
                    "",
                    effectiveReportRoot.toString(),
                    "false"
            ));
            Path reportPath = resolveReportPath(effectiveReportRoot, fileName);
            recordReportArtifact(reportPath.toString(), reportTemplate, normalizedTheme, sites);
            return reportPath.toString();
        } catch (IOException e) {
            log.error("Error preparing report directory {}: {}", reportDirectory, e.getMessage(), e);
            throw new ZapApiException("Error preparing report directory", e);
        }
    }

    private void recordReportArtifact(String reportPath, String reportTemplate, String theme, String sites) {
        if (scanHistoryLedgerService == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (hasText(reportTemplate)) {
            metadata.put("template", reportTemplate.trim());
        }
        if (hasText(theme)) {
            metadata.put("theme", theme.trim());
        }
        scanHistoryLedgerService.recordReportArtifact(reportPath, reportTemplate, sites, metadata);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String readReport(
            String reportPath,
            Integer maxChars
    ) {
        String normalizedReportPath = requireText(reportPath, "reportPath");
        int boundedMaxChars = validateMaxChars(maxChars);
        Path reportRoot = resolveReadReportDirectory(Paths.get(reportDirectory).toAbsolutePath().normalize());
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

    private Path resolveWriteReportDirectory(Path configuredReportRoot) {
        if (reportArtifactBoundary == null) {
            return workspaceScopedDirectory(configuredReportRoot);
        }
        return reportArtifactBoundary.resolveWriteDirectory(configuredReportRoot).toAbsolutePath().normalize();
    }

    private Path resolveReadReportDirectory(Path configuredReportRoot) {
        if (reportArtifactBoundary == null) {
            return workspaceScopedDirectory(configuredReportRoot);
        }
        return reportArtifactBoundary.resolveReadDirectory(configuredReportRoot).toAbsolutePath().normalize();
    }

    private Path workspaceScopedDirectory(Path configuredReportRoot) {
        return configuredReportRoot
                .resolve("workspaces")
                .resolve(workspacePathSegment(currentWorkspaceId()))
                .normalize();
    }

    private String currentWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        String workspaceId = clientWorkspaceResolver.resolveCurrentWorkspaceId();
        return hasText(workspaceId) ? workspaceId.trim() : "default-workspace";
    }

    private String workspacePathSegment(String workspaceId) {
        String normalized = hasText(workspaceId) ? workspaceId.trim() : "default-workspace";
        if (SAFE_WORKSPACE_SEGMENT.matcher(normalized).matches()) {
            return normalized;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "ws-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for workspace report paths", e);
        }
    }

}
