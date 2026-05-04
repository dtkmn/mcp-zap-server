package mcp.server.zap.core.service.protection;

import java.util.List;
import mcp.server.zap.core.history.ScanHistoryEntry;

/**
 * Optional access boundary for scan history and release-evidence visibility.
 */
public interface ScanHistoryAccessBoundary {

    boolean canCurrentRequesterAccess(ScanHistoryEntry entry);

    default List<ScanHistoryEntry> filterVisibleEntries(List<ScanHistoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(this::canCurrentRequesterAccess)
                .toList();
    }
}
