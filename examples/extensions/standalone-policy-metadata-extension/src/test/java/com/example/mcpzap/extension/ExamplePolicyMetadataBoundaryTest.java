package com.example.mcpzap.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExamplePolicyMetadataBoundaryTest {

    @Test
    @SuppressWarnings("unchecked")
    void enrichesMetadataThroughExtensionApiContract() {
        ExamplePolicyMetadataBoundary boundary = new ExamplePolicyMetadataBoundary();
        Map<String, Object> summary = new LinkedHashMap<>();

        boundary.enrichBundleSummary(summary, Map.of("tenant", "demo", "workspace", "local"));

        assertThat(summary).containsKey("exampleExtension");
        assertThat((Map<String, Object>) summary.get("exampleExtension"))
                .containsEntry("provider", "example-standalone-policy-metadata")
                .containsEntry("mode", "metadata-only")
                .containsEntry("enforcesAccess", false)
                .containsEntry("labelCount", 2);
    }
}
