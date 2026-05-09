package mcp.server.zap.extension.api.evidence;

import java.util.Map;

/**
 * Extension API hook for adding low-risk metadata to evidence records.
 */
public interface EvidenceMetadataEnricher {

    /**
     * Return metadata entries to merge into an evidence record.
     */
    Map<String, String> enrichEvidenceMetadata(EvidenceMetadataRequest request);
}
