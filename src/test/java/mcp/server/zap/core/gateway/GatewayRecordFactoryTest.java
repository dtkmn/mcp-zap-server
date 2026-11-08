package mcp.server.zap.core.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayRecordFactoryTest {
    private final GatewayRecordFactory factory = new GatewayRecordFactory();

    @Test
    void targetFromUrlNormalizesHostAndDropsQueryFromGatewayDescriptor() {
        TargetDescriptor target = factory.targetFromUrl(
                "https://EXAMPLE.com:8443/admin/login?token=secret#section",
                TargetDescriptor.Kind.WEB
        );

        assertThat(target.kind()).isEqualTo(TargetDescriptor.Kind.WEB);
        assertThat(target.baseUrl()).isEqualTo("https://example.com:8443/admin/login");
        assertThat(target.displayName()).isEqualTo("example.com");
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE_SCAN, active scanning, active scanning",
            "CRAWL, , crawl",
            "ACTIVE_SCAN, '   ', active scan",
            "REPORT_GENERATE, , report generate"
    })
    void unsupportedCapabilityFailureCarriesControlledMessageAndBoundedOperatorDetails(
            EngineCapability capability, String operationLabel, String expectedLabel) {
        EngineAdapter engineAdapter = new EngineAdapter() {
            @Override
            public String engineId() {
                return "metadata";
            }

            @Override
            public String displayName() {
                return "Metadata Only";
            }

            @Override
            public java.util.Set<EngineCapability> supportedCapabilities() {
                return java.util.Set.of(EngineCapability.FINDINGS_READ);
            }
        };

        assertThatThrownBy(() -> factory.requireCapability(engineAdapter, capability, operationLabel))
                .isInstanceOfSatisfying(UnsupportedEngineCapabilityException.class, exception -> {
                    assertThat(exception).hasMessage("Engine 'Metadata Only' does not support " + expectedLabel + ".");
                    assertThat(exception.engineId()).isEqualTo("metadata");
                    assertThat(exception.engineDisplayName()).isEqualTo("Metadata Only");
                    assertThat(exception.capability()).isEqualTo(capability);
                    assertThat(exception.operationLabel()).isEqualTo(expectedLabel);
                    assertThat(exception.getMessage()).doesNotContain("https://", "token=", "secret");
                });
    }
}
