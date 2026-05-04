package mcp.server.zap.core.service.protection;

import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationRegistryTest {

    @Test
    void tracksAndReleasesDirectAndAutomationOperationsByWorkspace() {
        AbuseProtectionProperties properties = new AbuseProtectionProperties();
        properties.setOperationStaleAfterSeconds(3600);

        OperationRegistry registry = new OperationRegistry(properties);
        registry.registerDirectScan("active:1", "workspace-one");
        registry.registerDirectScan("spider:2", "workspace-one");
        registry.registerAutomationPlan("plan-1", "workspace-two");

        assertThat(registry.countDirectScans()).isEqualTo(2);
        assertThat(registry.countDirectScans("workspace-one")).isEqualTo(2);
        assertThat(registry.countAutomationPlans()).isEqualTo(1);
        assertThat(registry.countAutomationPlans("workspace-two")).isEqualTo(1);

        registry.releaseDirectScansByPrefix("active:");
        registry.releaseAutomationPlan("plan-1");

        assertThat(registry.countDirectScans()).isEqualTo(1);
        assertThat(registry.countAutomationPlans()).isZero();
    }
}
