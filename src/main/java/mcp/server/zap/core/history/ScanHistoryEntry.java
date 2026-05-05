package mcp.server.zap.core.history;

import java.time.Instant;
import java.util.Map;
import mcp.server.zap.core.gateway.TargetDescriptor;

/**
 * Shared evidence-ledger entry for scan runs, durable queue jobs, and report artifacts.
 */
public record ScanHistoryEntry(
        String id,
        Instant recordedAt,
        String evidenceType,
        String operationKind,
        String status,
        String engineId,
        TargetDescriptor target,
        String executionMode,
        String backendReference,
        String artifactId,
        String artifactType,
        String artifactLocation,
        String mediaType,
        String clientId,
        String workspaceId,
        Map<String, String> metadata
) {
    public ScanHistoryEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
