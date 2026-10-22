package mcp.server.zap.core.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import mcp.server.zap.core.service.UrlValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidationConfigurationTest {
    private static final String FORMER_DEFAULT_LIST = "localhost,127.0.0.1,0.0.0.0,169.254.0.0/16,"
            + "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    @Test
    void absentOrExplicitlyBlankBlacklistKeepsBuiltInGuards() {
        for (Map<String, Object> variables : java.util.List.of(
                Map.<String, Object>of(), Map.<String, Object>of("ZAP_URL_BLACKLIST", ""))) {
            runner(variables).run(context -> {
                assertThat(context).hasNotFailed();
                UrlValidationService policy = context.getBean(UrlValidationService.class);
                for (String target : java.util.List.of("http://127.0.0.1", "http://[::1]",
                        "http://10.1.2.3", "http://[fd00::1]")) {
                    assertThatThrownBy(() -> policy.validateUrl(target))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            });
        }
    }

    @Test
    void environmentOptInsWorkWithoutAnExplicitBlacklist() {
        runner(optIns()).run(context -> {
            assertThat(context).hasNotFailed();
            UrlValidationService policy = context.getBean(UrlValidationService.class);
            for (String target : java.util.List.of("http://127.0.0.1", "http://[::1]",
                    "http://10.1.2.3", "http://[fd00::1]")) {
                assertThatCode(() -> policy.validateUrl(target)).doesNotThrowAnyException();
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZAP_URL_BLACKLIST", "ZAP_SCAN_URL_BLACKLIST"})
    void environmentBlacklistRemainsAuthoritativeEvenForFormerDefaultCidr(String name) {
        var variables = new java.util.HashMap<>(optIns());
        variables.put(name, "10.0.0.0/8");
        variables.put("ZAP_URL_WHITELIST", "*");

        runner(variables).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(UrlValidationService.class).validateUrl("http://10.1.2.3"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blacklisted");
        });
    }

    @Test
    void directCustomPropertyOverridesEnvironmentAndKeepsExplicitRestrictions() {
        var variables = new java.util.HashMap<>(optIns());
        variables.put("ZAP_URL_BLACKLIST", "10.0.0.0/8");

        runner(variables).withPropertyValues("zap.scan.url.blacklist=127.0.0.0/8",
                "zap.scan.url.whitelist=*").run(context -> {
            assertThat(context).hasNotFailed();
            UrlValidationService policy = context.getBean(UrlValidationService.class);
            assertThatThrownBy(() -> policy.validateUrl("http://127.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blacklisted");
            assertThatCode(() -> policy.validateUrl("http://10.1.2.3")).doesNotThrowAnyException();
        });
    }

    @Test
    void explicitlySuppliedFormerDefaultListIsNeverGuessedToBeImplicit() {
        runner(optIns()).withPropertyValues("zap.scan.url.blacklist=" + FORMER_DEFAULT_LIST)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UrlValidationService policy = context.getBean(UrlValidationService.class);
                    for (String target : java.util.List.of("http://localhost", "http://127.0.0.1",
                            "http://10.1.2.3", "http://172.18.0.2", "http://192.168.1.50")) {
                        assertThatThrownBy(() -> policy.validateUrl(target))
                                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blacklisted");
                    }
                });
    }

    @Test
    void explicitlyDisabledValidationRetainsItsExistingBypass() {
        runner(Map.of("ZAP_URL_VALIDATION_ENABLED", "false", "ZAP_URL_BLACKLIST", "*"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatCode(() -> context.getBean(UrlValidationService.class).validateUrl("http://127.0.0.1"))
                            .doesNotThrowAnyException();
                });
    }

    private Map<String, Object> optIns() {
        return Map.of("ZAP_ALLOW_LOCALHOST", "true", "ZAP_ALLOW_PRIVATE_NETWORKS", "true");
    }

    private ApplicationContextRunner runner(Map<String, Object> variables) {
        return new ApplicationContextRunner().withInitializer(context -> {
            context.getEnvironment().getPropertySources().replace("systemEnvironment",
                    new SystemEnvironmentPropertySource("systemEnvironment", variables));
            try {
                new YamlPropertySourceLoader().load("shipped-application", new ClassPathResource("application.yml"))
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).withUserConfiguration(UrlValidationService.class);
    }
}
