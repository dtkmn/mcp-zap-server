package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityStartupConfigurationTest {
    private static final String MIGRATION_MESSAGE = "mcp.server.apiKey is no longer supported";

    @Test
    void shippedConfigurationRequiresAnOperatorSuppliedKey() {
        runner(Map.of()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "MCP security mode 'api-key' requires at least one API key. "
                            + "Set MCP_API_KEY or mcp.server.auth.apiKeys[].key.");
        });
    }

    @Test
    void environmentShortcutRegistersTheDefaultClient() {
        runner(Map.of("MCP_API_KEY", "operator-supplied-key")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyProperties.class).getApiKeys()).singleElement()
                    .satisfies(client -> {
                        assertThat(client.getKey()).isEqualTo("operator-supplied-key");
                        assertThat(client.getClientId()).isEqualTo("default-client");
                        assertThat(client.getScopes()).containsExactly("*");
                    });
        });
    }

    @Test
    void customClientsReplaceTheDefaultWithoutRegisteringAnExtraEnvironmentKey() {
        runner(Map.of("MCP_API_KEY", "unused-environment-key"))
                .withPropertyValues(
                        "mcp.server.auth.apiKeys[0].clientId=reader",
                        "mcp.server.auth.apiKeys[0].key=reader-key",
                        "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                        "mcp.server.auth.apiKeys[1].clientId=reporter",
                        "mcp.server.auth.apiKeys[1].key=reporter-key",
                        "mcp.server.auth.apiKeys[1].scopes[0]=zap:report:read")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApiKeyProperties.class).getApiKeys())
                            .extracting(ApiKeyProperties.ApiKeyClient::getKey)
                            .containsExactly("reader-key", "reporter-key");
                });
    }

    @Test
    void customClientNeedsNoEnvironmentShortcut() {
        runner(Map.of())
                .withPropertyValues(
                        "mcp.server.auth.apiKeys[0].clientId=reader",
                        "mcp.server.auth.apiKeys[0].key=reader-key",
                        "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shippedConfigurationRejectsPlaceholderCredentialsByDefault() {
        runner(Map.of("MCP_API_KEY", "changeme-default-key"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains("Placeholder MCP API key detected");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mcp.server.apiKey", "mcp.server.api-key"})
    void removedPropertyFailsWithSecretFreeMigrationInstructions(String propertyName) {
        runner(Map.of("MCP_API_KEY", "registered-key"))
                .withPropertyValues(propertyName + "=unregistered-legacy-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains(MIGRATION_MESSAGE, "mcp.server.auth.apiKeys", "MCP_API_KEY")
                            .doesNotContain("unregistered-legacy-secret", "registered-key");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"MCP_SERVER_APIKEY", "MCP_SERVER_API_KEY"})
    void removedEnvironmentPropertyFailsWithMigrationInstructions(String variableName) {
        runner(Map.of("MCP_API_KEY", "registered-key", variableName, "unregistered-legacy-secret"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains(MIGRATION_MESSAGE)
                            .doesNotContain("unregistered-legacy-secret");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "api-key"})
    void removedPropertyIsRejectedEvenWhenAuthenticationIsDisabled(String mode) {
        runner(Map.of())
                .withPropertyValues("mcp.server.security.mode=" + mode,
                        "mcp.server.security.enabled=" + mode.equals("none"), "mcp.server.apiKey=old-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage()).contains(MIGRATION_MESSAGE);
                });
    }

    @Test
    void blankRemovedPropertyDoesNotPreventRegisteredClientStartup() {
        runner(Map.of("MCP_API_KEY", "registered-key"))
                .withPropertyValues("mcp.server.apiKey=   ")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "test", "local,test"})
    void placeholdersRequireAnExplicitIsolatedProfileAndAllowance(String profiles) {
        runner(Map.of("MCP_API_KEY", "changeme-default-key", "MCP_SECURITY_ALLOW_PLACEHOLDER_API_KEY", "true"))
                .withPropertyValues("spring.profiles.active=" + profiles)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prod", "production", "dev,prod", "test,staging"})
    void placeholderAllowanceDoesNotApplyToProductionOrUnspecifiedProfiles(String profiles) {
        runner(Map.of("MCP_API_KEY", "changeme-default-key", "MCP_SECURITY_ALLOW_PLACEHOLDER_API_KEY", "true"))
                .withPropertyValues("spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()).getMessage())
                            .contains("Placeholder MCP API key detected");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "api-key"})
    void explicitNoAuthenticationDoesNotRequireAKey(String mode) {
        runner(Map.of())
                .withPropertyValues("mcp.server.security.mode=" + mode,
                        "mcp.server.security.enabled=" + mode.equals("none"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner runner(Map<String, Object> environmentVariables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().replace("systemEnvironment",
                            new SystemEnvironmentPropertySource("systemEnvironment", environmentVariables));
                    try {
                        new YamlPropertySourceLoader().load("shipped-application", new ClassPathResource("application.yml"))
                                .forEach(context.getEnvironment().getPropertySources()::addLast);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withUserConfiguration(StartupConfiguration.class);
    }

    private Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ApiKeyProperties.class)
    @Import(SecurityStartupValidator.class)
    static class StartupConfiguration {
    }
}
