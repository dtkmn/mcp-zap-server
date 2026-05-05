package mcp.server.zap.core.gateway;

import org.junit.jupiter.api.Test;

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

    @Test
    void unsupportedCapabilityFailsWithControlledMessage() {
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

        assertThatThrownBy(() -> factory.requireCapability(
                engineAdapter,
                EngineCapability.GUIDED_ATTACK,
                "guided attack"
        ))
                .isInstanceOf(UnsupportedEngineCapabilityException.class)
                .hasMessage("Engine 'Metadata Only' does not support guided attack.");
    }

    @Test
    void unsupportedCapabilityExceptionCarriesBoundedOperatorDetailsOnly() {
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

        assertThatThrownBy(() -> factory.requireCapability(
                engineAdapter,
                EngineCapability.GUIDED_ATTACK,
                "guided attack"
        ))
                .isInstanceOfSatisfying(UnsupportedEngineCapabilityException.class, exception -> {
                    assertThat(exception.engineId()).isEqualTo("metadata");
                    assertThat(exception.engineDisplayName()).isEqualTo("Metadata Only");
                    assertThat(exception.capability()).isEqualTo(EngineCapability.GUIDED_ATTACK);
                    assertThat(exception.operationLabel()).isEqualTo("guided attack");
                    assertThat(exception.getMessage()).doesNotContain("https://", "token=", "secret");
                });
    }
}
