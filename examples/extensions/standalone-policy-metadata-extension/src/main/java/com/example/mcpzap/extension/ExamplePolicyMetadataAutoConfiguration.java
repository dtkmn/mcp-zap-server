package com.example.mcpzap.extension;

import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(
        prefix = "example.mcp-zap.policy-metadata-extension",
        name = "enabled",
        havingValue = "true"
)
public class ExamplePolicyMetadataAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PolicyBundleAccessBoundary.class)
    PolicyBundleAccessBoundary examplePolicyMetadataBoundary() {
        return new ExamplePolicyMetadataBoundary();
    }
}
