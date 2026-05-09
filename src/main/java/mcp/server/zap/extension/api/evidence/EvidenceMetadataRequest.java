package mcp.server.zap.extension.api.evidence;

import java.util.Map;

/**
 * Stable evidence metadata input exposed to extensions.
 */
public record EvidenceMetadataRequest(
        String evidenceType,
        String operationKind,
        String status,
        String targetUrl,
        Map<String, String> existingMetadata
) {
    public EvidenceMetadataRequest {
        existingMetadata = existingMetadata == null ? Map.of() : Map.copyOf(existingMetadata);
    }
}
