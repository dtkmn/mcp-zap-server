package mcp.server.zap.core.configuration;

import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import mcp.server.zap.core.gateway.EngineMaintenanceGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.zaproxy.clientapi.core.ClientApi;

import static org.assertj.core.api.Assertions.assertThat;

class ZapApiConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ZapApiConfig.class, AddonMaintenanceProperties.class, EngineMaintenanceGate.class)
            .withPropertyValues("zap.server.apiKey=test-api-key");

    @Test
    void defaultSettingsCreateTheSharedTimeoutClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ClientApi.class);
            assertThat(context.getBean(ClientApi.class)).isInstanceOf(TimeoutZapClientApi.class);
        });
    }

    @Test
    void positiveTimeoutOverridesAreAccepted() {
        contextRunner.withPropertyValues(
                        "zap.server.connect-timeout-ms=250",
                        "zap.server.read-timeout-ms=500")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ClientApi.class);
                    ClientApi client = context.getBean(ClientApi.class);
                    assertThat(client).isInstanceOf(TimeoutZapClientApi.class);
                    assertThat(ReflectionTestUtils.getField(client, "connectTimeoutMs")).isEqualTo(250);
                    assertThat(ReflectionTestUtils.getField(client, "readTimeoutMs")).isEqualTo(500);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "connect-timeout-ms, 0",
            "connect-timeout-ms, -1",
            "read-timeout-ms, 0",
            "read-timeout-ms, -1"
    })
    void nonPositiveTimeoutsFailStartup(String property, int value) {
        contextRunner.withPropertyValues("zap.server." + property + "=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }
}
