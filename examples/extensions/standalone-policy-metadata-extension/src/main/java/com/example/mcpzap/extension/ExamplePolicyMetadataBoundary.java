package com.example.mcpzap.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;

final class ExamplePolicyMetadataBoundary implements PolicyBundleAccessBoundary {
    private static final String PROVIDER_ID = "example-standalone-policy-metadata";

    @Override
    public void enrichBundleSummary(Map<String, Object> bundleSummary, Map<String, String> bundleLabels) {
        if (bundleSummary == null) {
            return;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", PROVIDER_ID);
        metadata.put("mode", "metadata-only");
        metadata.put("enforcesAccess", false);
        if (bundleLabels != null && !bundleLabels.isEmpty()) {
            metadata.put("labelCount", bundleLabels.size());
        }
        bundleSummary.put("exampleExtension", metadata);
    }
}
