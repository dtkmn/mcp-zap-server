package mcp.server.zap.core.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryScanHistoryStore implements ScanHistoryStore {
    private static final Comparator<ScanHistoryEntry> ENTRY_ORDER = Comparator
            .comparing(ScanHistoryEntry::recordedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ScanHistoryEntry::id, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed();

    private final ConcurrentHashMap<String, ScanHistoryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public ScanHistoryEntry append(ScanHistoryEntry entry) {
        entries.put(entry.id(), entry);
        return entry;
    }

    @Override
    public Optional<ScanHistoryEntry> load(String entryId) {
        return Optional.ofNullable(entries.get(entryId));
    }

    @Override
    public List<ScanHistoryEntry> list(ScanHistoryQuery query, int limit) {
        return entries.values().stream()
                .filter(entry -> matches(query, entry))
                .sorted(ENTRY_ORDER)
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public void deleteBefore(Instant cutoff) {
        if (cutoff == null) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry.getValue().recordedAt() != null
                && entry.getValue().recordedAt().isBefore(cutoff));
    }

    private boolean matches(ScanHistoryQuery query, ScanHistoryEntry entry) {
        if (query == null) {
            return true;
        }
        if (hasText(query.evidenceType()) && !query.evidenceType().equalsIgnoreCase(entry.evidenceType())) {
            return false;
        }
        if (hasText(query.status()) && !query.status().equalsIgnoreCase(entry.status())) {
            return false;
        }
        if (hasText(query.workspaceId()) && !query.workspaceId().equals(entry.workspaceId())) {
            return false;
        }
        if (hasText(query.targetContains())) {
            String needle = query.targetContains().trim().toLowerCase();
            String haystack = String.join(" ",
                    safe(entry.target() == null ? null : entry.target().baseUrl()),
                    safe(entry.target() == null ? null : entry.target().displayName()),
                    safe(entry.artifactLocation())
            ).toLowerCase();
            return haystack.contains(needle);
        }
        return true;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
