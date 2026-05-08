package mcp.server.zap.examples.extensions.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Minimal public-safe extension proof that enriches policy dry-run metadata.
 */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "mcp.server.sample.policy-metadata-extension",
        name = "enabled",
        havingValue = "true"
)
public class SamplePolicyMetadataExtensionConfiguration {

    @Bean
    @ConditionalOnMissingBean(PolicyBundleAccessBoundary.class)
    PolicyBundleAccessBoundary samplePolicyMetadataBoundary() {
        return new SamplePolicyMetadataBoundary();
    }

    static final class SamplePolicyMetadataBoundary implements PolicyBundleAccessBoundary {
        private static final String PROVIDER_ID = "sample-policy-metadata";

        @Override
        public void enrichBundleSummary(Map<String, Object> bundleSummary, Map<String, String> bundleLabels) {
            if (bundleSummary == null) {
                return;
            }

            Map<String, Object> metadata = baseMetadata();
            if (bundleLabels != null && !bundleLabels.isEmpty()) {
                metadata.put("observedLabelKeys", bundleLabels.keySet().stream()
                        .filter(key -> key != null && !key.isBlank())
                        .sorted()
                        .toList());
            }
            bundleSummary.put("sampleExtension", metadata);
        }

        @Override
        public void enrichRequestSummary(Map<String, Object> requestSummary) {
            if (requestSummary == null) {
                return;
            }
            requestSummary.put("sampleExtension", baseMetadata());
        }

        private Map<String, Object> baseMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", PROVIDER_ID);
            metadata.put("mode", "metadata-only");
            metadata.put("enforcesAccess", false);
            return metadata;
        }
    }
}
