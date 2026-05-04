package mcp.server.zap.core.gateway;

/**
 * Shared gateway record for scan execution lifecycle state.
 */
public record ScanRunRecord(
        String engineId,
        String runId,
        String operationKind,
        String status,
        TargetDescriptor target,
        String executionMode,
        String backendReference
) {
}
