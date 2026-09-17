package mcp.server.zap.core.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.NetworkDefaults;
import mcp.server.zap.core.service.ZapInitializationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ZapInitializationPropertiesTest {

    @ParameterizedTest
    @MethodSource("targetTimeoutSettings")
    void targetConnectionTimeoutReachesStartupConfiguration(int expectedTimeout, String[] properties) {
        EngineRuntimeAccess runtimeAccess = mock(EngineRuntimeAccess.class);
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class, ZapInitializationService.class)
                .withBean(EngineRuntimeAccess.class, () -> runtimeAccess)
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("application", new ClassPathResource("application.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withPropertyValues(properties)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ArgumentCaptor<NetworkDefaults> defaults = ArgumentCaptor.forClass(NetworkDefaults.class);
                    verify(runtimeAccess).applyNetworkDefaults(defaults.capture());
                    assertThat(defaults.getValue().connectionTimeoutInSecs()).isEqualTo(expectedTimeout);
                });
    }

    private static Stream<Arguments> targetTimeoutSettings() {
        return Stream.of(
                Arguments.of(300, new String[] {}),
                Arguments.of(75, new String[] {"ZAP_CONNECTION_TIMEOUT=75"}),
                Arguments.of(120, new String[] {"ZAP_INIT_CONNECTION_TIMEOUT=120"}),
                Arguments.of(120, new String[] {"ZAP_CONNECTION_TIMEOUT=75", "ZAP_INIT_CONNECTION_TIMEOUT=120"})
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ZapInitializationProperties.class)
    static class BindingConfiguration {
    }
}
