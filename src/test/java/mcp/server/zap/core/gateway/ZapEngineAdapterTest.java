package mcp.server.zap.core.gateway;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZapEngineAdapterTest {
    private final ZapEngineAdapter adapter = new ZapEngineAdapter();

    @Test
    void declaresTheCurrentZapGatewayCapabilityContract() {
        assertThat(adapter.engineId()).isEqualTo("zap");
        assertThat(adapter.displayName()).isEqualTo("OWASP ZAP");
        assertThat(adapter.supportedCapabilities()).containsExactlyInAnyOrder(
                EngineCapability.TARGET_IMPORT,
                EngineCapability.GUIDED_CRAWL,
                EngineCapability.GUIDED_ATTACK,
                EngineCapability.FINDINGS_READ,
                EngineCapability.REPORT_GENERATE,
                EngineCapability.FORM_AUTH_BOOTSTRAP,
                EngineCapability.HEADER_AUTH_REFERENCE
        );
    }

    @Test
    void capabilityLookupIsExplicitAndNullSafe() {
        EngineAdapter adapterWithNoCapabilities = new EngineAdapter() {
            @Override
            public String engineId() {
                return "metadata-only";
            }

            @Override
            public String displayName() {
                return "Metadata Only";
            }

            @Override
            public Set<EngineCapability> supportedCapabilities() {
                return Set.of();
            }
        };

        assertThat(adapter.supports(EngineCapability.GUIDED_ATTACK)).isTrue();
        assertThat(adapter.supports(null)).isFalse();
        assertThat(adapterWithNoCapabilities.supports(EngineCapability.GUIDED_ATTACK)).isFalse();
    }
}
