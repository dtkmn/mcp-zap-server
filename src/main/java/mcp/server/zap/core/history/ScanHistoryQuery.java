package mcp.server.zap.core.history;

/**
 * Bounded query filters for the shared scan history ledger.
 */
public record ScanHistoryQuery(
        String evidenceType,
        String status,
        String targetContains,
        String workspaceId
) {
}
