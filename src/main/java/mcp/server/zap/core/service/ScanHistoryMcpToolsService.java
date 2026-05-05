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
            description = "Export a bounded scan history ledger snapshot as JSON for release evidence or handoff."
    )
    public String exportHistory(
            @ToolParam(required = false, description = "Optional evidence type filter: scan_job, scan_run, or report_artifact") String evidenceType,
            @ToolParam(required = false, description = "Optional status filter") String status,
            @ToolParam(required = false, description = "Optional target or artifact substring filter") String target,
            @ToolParam(required = false, description = "Optional maximum entries to export, bounded by server configuration") Integer limit
    ) {
        return scanHistoryLedgerService.exportHistory(evidenceType, status, target, limit);
    }
}
