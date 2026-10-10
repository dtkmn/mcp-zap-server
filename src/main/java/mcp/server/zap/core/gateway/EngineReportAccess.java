package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing access contract for engine generated reports.
 */
public interface EngineReportAccess {

    List<String> listReportTemplates();

    String generateReport(ReportGenerationRequest request);

    /**
     * Generates a report for the validated, canonical URL prefixes supplied by ReportService.
     * Engines may preserve equivalent root URI spellings, but must not broaden the target scope.
     * The default retains the native filter unchanged.
     */
    default String generateScopedReport(ReportGenerationRequest request) {
        return generateReport(request);
    }

    record ReportGenerationRequest(
            String title,
            String template,
            String theme,
            String description,
            String contexts,
            String sites,
            String sections,
            String includedConfidences,
            String includedRisks,
            String reportFileName,
            String reportFileNamePattern,
            String reportDirectory,
            String display
    ) {
    }
}
