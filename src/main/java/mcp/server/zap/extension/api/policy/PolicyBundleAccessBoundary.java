package mcp.server.zap.extension.api.policy;

import java.util.List;
import java.util.Map;

/**
 * Extension API boundary for requester-scoped policy preview access and metadata enrichment.
 */
public interface PolicyBundleAccessBoundary {

    /**
     * Validate whether the current requester can evaluate the supplied policy bundle labels.
     */
    default List<String> validateCurrentRequesterAccess(Map<String, String> bundleLabels) {
        if (bundleLabels == null) {
            return List.of("bundle.metadata.labels must be available for access validation");
        }
        return List.of();
    }

    /**
     * Enrich the response bundle summary with boundary-aware metadata.
     */
    default void enrichBundleSummary(Map<String, Object> bundleSummary, Map<String, String> bundleLabels) {
    }

    /**
     * Enrich the response request summary with boundary-aware metadata.
     */
    default void enrichRequestSummary(Map<String, Object> requestSummary) {
    }
}
