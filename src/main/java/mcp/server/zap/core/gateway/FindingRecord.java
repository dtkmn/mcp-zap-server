package mcp.server.zap.core.gateway;

/**
 * Shared gateway record for a normalized finding summary.
 */
public record FindingRecord(
        String engineId,
        String findingKey,
        String severity,
        String title,
        TargetDescriptor target,
        String category,
        String evidenceSummary
) {
}
