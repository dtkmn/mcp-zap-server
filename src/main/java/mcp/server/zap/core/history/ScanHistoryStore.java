package mcp.server.zap.core.history;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScanHistoryStore {

    ScanHistoryEntry append(ScanHistoryEntry entry);

    Optional<ScanHistoryEntry> load(String entryId);

    List<ScanHistoryEntry> list(ScanHistoryQuery query, int limit);

    void deleteBefore(Instant cutoff);
}
