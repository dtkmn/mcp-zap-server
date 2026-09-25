package mcp.server.zap.core.service;

import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP adapter for the shared scan history and release-evidence ledger.
 */
@Service
public class ScanHistoryMcpToolsService {
    private final ScanHistoryLedgerService scanHistoryLedgerService;

    public ScanHistoryMcpToolsService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    @Tool(
            name = "zap_scan_history_list",
            description = "List recent scan history and release-evidence entries, including queued scans, direct scan starts, and generated report artifacts."
    )
    public String listHistory(
            @ToolParam(required = false, description = "Optional evidence type filter: scan_job, scan_run, or report_artifact") String evidenceType,
            @ToolParam(required = false, description = "Optional status filter such as queued, running, succeeded, failed, cancelled, started, or generated") String status,
            @ToolParam(required = false, description = "Optional target or artifact substring filter") String target,
            @ToolParam(required = false, description = "Optional maximum entries to return, bounded by server configuration") Integer limit
    ) {
        return scanHistoryLedgerService.listHistory(evidenceType, status, target, limit);
    }

    @Tool(
            name = "zap_scan_history_get",
            description = "Read one scan history or release-evidence entry by ID."
    )
    public String getHistoryEntry(
            @ToolParam(description = "Entry ID returned by zap_scan_history_list") String entryId
    ) {
        return scanHistoryLedgerService.getHistoryEntry(entryId);
    }

    @Tool(
            name = "zap_scan_history_export",
            description = """
                    Export scan history as inline JSON text for internal evidence processing; no export file is created.
                    Use zap_scan_history_list for a readable listing, zap_scan_history_release_evidence for a summary bundle,
                    or zap_scan_history_customer_handoff for a customer-facing summary.
                    Returns version, generatedAt, retentionDays, entryCount, and entries containing scan/job/artifact records,
                    including internal IDs, paths, and metadata; review before sharing.
                    Only records accessible to the caller in the current workspace are included, newest first.
                    Omitted or blank filters select all accessible records; supplied filters combine with AND.
                    The snapshot is capped by limit, has no pagination, and returns an empty entries array when nothing matches.
                    It does not start scans or contact targets. Querying may delete expired stored ledger entries under
                    the configured retention policy; it does not refresh scan outcomes from ZAP.
                    """
    )
    public String exportHistory(
            @ToolParam(required = false, description = "Optional case-insensitive exact type filter: scan_job, scan_run, or report_artifact. Omit or leave blank for all types.") String evidenceType,
            @ToolParam(required = false, description = "Optional case-insensitive exact status filter: queued, running, succeeded, failed, or cancelled for jobs; started for direct scans; generated for reports. Omit or leave blank for all statuses.") String status,
            @ToolParam(required = false, description = "Optional case-insensitive substring of the target URL, target display name, or artifact location, e.g. api.example.com. Omit or leave blank for all targets.") String target,
            @ToolParam(required = false, description = "Positive maximum number of entries. Omission uses the configured export maximum (500 by default); larger values are capped to that maximum. Zero or negative values are rejected.") Integer limit
    ) {
        return scanHistoryLedgerService.exportHistory(evidenceType, status, target, limit);
    }

    @Tool(
            name = "zap_scan_history_release_evidence",
            description = "Export a release or pilot handoff evidence bundle with summary counts, target coverage, warnings, and bounded ledger entries."
    )
    public String exportReleaseEvidence(
            @ToolParam(required = false, description = "Optional release, pilot, or handoff label included in the exported bundle") String releaseName,
            @ToolParam(required = false, description = "Optional target or artifact substring filter for the evidence window") String target,
            @ToolParam(required = false, description = "Optional maximum entries to export, bounded by server configuration") Integer limit
    ) {
        return scanHistoryLedgerService.exportReleaseEvidence(releaseName, target, limit);
    }

    @Tool(
            name = "zap_scan_history_customer_handoff",
            description = "Generate a customer-safe Markdown handoff summary from scan history without raw internal IDs, backend references, workspace IDs, or metadata."
    )
    public String exportCustomerHandoff(
            @ToolParam(required = false, description = "Optional release, pilot, or customer handoff label included in the summary") String handoffName,
            @ToolParam(required = false, description = "Optional evidence-window selector used internally; the raw selector is never echoed in customer-facing output") String target,
            @ToolParam(required = false, description = "Optional maximum entries to review, bounded by server configuration") Integer limit
    ) {
        return scanHistoryLedgerService.exportCustomerHandoff(handoffName, target, limit);
    }
}
