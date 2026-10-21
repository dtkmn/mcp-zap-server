package mcp.server.zap.core.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.service.authz.ToolAuthorizationService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class QuickStartScopesConfigurationTest {
    private static final String EXPLICIT_SCOPES = "mcp:tools:list,zap:scan:read,zap:scan:crawl:run,"
            + "zap:scan:stop,zap:alerts:read,zap:report:read,zap:report:generate";
    private static final List<String> EXPECTED_SCOPES = List.of(EXPLICIT_SCOPES.split(","));

    @Test
    void packagedDefaultClientKeepsWildcardAndEnforcementDefaults() {
        runner(Map.of("MCP_API_KEY", "operator-key")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyProperties.class).getApiKeys().getFirst().getScopes())
                    .containsExactly("*");
            ToolAuthorizationProperties authorization = context.getBean(ToolAuthorizationProperties.class);
            assertThat(authorization.getMode()).isEqualTo(ToolAuthorizationProperties.Mode.ENFORCE);
            assertThat(authorization.isAllowWildcard()).isTrue();
        });
    }

    @Test
    void explicitEmptyScopesGrantNoToolPermissions() {
        runner(nativeAliases("")).run(context -> {
            assertThat(context).hasNotFailed();
            List<String> scopes = context.getBean(ApiKeyProperties.class).getApiKeys().getFirst().getScopes();
            assertThat(scopes).isEmpty();
            ToolAuthorizationService authorization = authorization(context.getBean(ToolAuthorizationProperties.class));
            assertThat(authorization.authorizeToolsList(scopes).allowed()).isFalse();
            assertThat(authorization.authorizeToolCall(scopes, "zap_crawl_start").allowed()).isFalse();
        });
    }

    @Test
    void nativeAliasesBindOperatorSelectedScopesAndIdentity() {
        runner(nativeAliases(EXPLICIT_SCOPES)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ApiKeyProperties.class).getApiKeys()).singleElement()
                    .satisfies(client -> {
                        assertThat(client.getClientId()).isEqualTo("restricted-client");
                        assertThat(client.getKey()).isEqualTo("operator-key");
                        assertThat(client.getScopes()).containsExactlyElementsOf(EXPECTED_SCOPES);
                    });
        });
    }

    @Test
    void existingExplicitCustomClientListReplacesThePackagedDefault() {
        runner(Map.of("MCP_API_KEY", "unused-key"))
                .withPropertyValues(
                        "mcp.server.auth.apiKeys[0].clientId=reader",
                        "mcp.server.auth.apiKeys[0].key=reader-key",
                        "mcp.server.auth.apiKeys[0].name=Report reader",
                        "mcp.server.auth.apiKeys[0].workspaceId=report-workspace",
                        "mcp.server.auth.apiKeys[0].scopes[0]=zap:report:read",
                        "mcp.server.auth.apiKeys[1].clientId=legacy-client",
                        "mcp.server.auth.apiKeys[1].key=legacy-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    List<ApiKeyProperties.ApiKeyClient> clients = context.getBean(ApiKeyProperties.class).getApiKeys();
                    assertThat(clients).hasSize(2);
                    assertThat(clients.getFirst().getClientId()).isEqualTo("reader");
                    assertThat(clients.getFirst().getKey()).isEqualTo("reader-key");
                    assertThat(clients.getFirst().getName()).isEqualTo("Report reader");
                    assertThat(clients.getFirst().getWorkspaceId()).isEqualTo("report-workspace");
                    assertThat(clients.getFirst().getScopes()).containsExactly("zap:report:read");
                    assertThat(clients.getLast().getClientId()).isEqualTo("legacy-client");
                    assertThat(clients.getLast().getScopes()).containsExactly("*");
                });
    }

    @Test
    void nativeIndexedClientScopesAndMetadataArePreserved() {
        Map<String, Object> variables = Map.ofEntries(
                Map.entry("MCP_API_KEY", "unused-packaged-key"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_CLIENTID", "reader"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_KEY", "reader-key"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_NAME", "Report reader"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_WORKSPACEID", "shared-report-workspace"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_SCOPES_0", "zap:report:read"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_0_SCOPES_1", "mcp:tools:list"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_1_CLIENTID", "legacy-client"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_1_KEY", "legacy-key"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_1_NAME", "Legacy operator"),
                Map.entry("MCP_SERVER_AUTH_APIKEYS_1_WORKSPACEID", "legacy-workspace"));

        runner(variables).run(context -> {
            assertThat(context).hasNotFailed();
            List<ApiKeyProperties.ApiKeyClient> clients = context.getBean(ApiKeyProperties.class).getApiKeys();
            assertThat(clients).hasSize(2);
            ApiKeyProperties.ApiKeyClient reader = clients.getFirst();
            assertThat(reader.getClientId()).isEqualTo("reader");
            assertThat(reader.getKey()).isEqualTo("reader-key");
            assertThat(reader.getName()).isEqualTo("Report reader");
            assertThat(reader.getWorkspaceId()).isEqualTo("shared-report-workspace");
            assertThat(reader.getScopes()).containsExactly("zap:report:read", "mcp:tools:list");
            ApiKeyProperties.ApiKeyClient legacy = clients.getLast();
            assertThat(legacy.getClientId()).isEqualTo("legacy-client");
            assertThat(legacy.getKey()).isEqualTo("legacy-key");
            assertThat(legacy.getName()).isEqualTo("Legacy operator");
            assertThat(legacy.getWorkspaceId()).isEqualTo("legacy-workspace");
            assertThat(legacy.getScopes()).containsExactly("*");
            ToolAuthorizationService authorization = authorization(context.getBean(ToolAuthorizationProperties.class));
            assertThat(authorization.authorizeToolCall(reader.getScopes(), "zap_report_read").allowed()).isTrue();
            assertThat(authorization.authorizeToolCall(reader.getScopes(), "zap_crawl_start").allowed()).isFalse();
            assertThat(authorization.authorizeToolCall(legacy.getScopes(), "zap_attack_start").allowed()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "zap_crawl_start", "zap_crawl_status", "zap_crawl_stop", "zap_passive_scan_status",
            "zap_passive_scan_wait", "zap_findings_summary", "zap_findings_details", "zap_report_generate",
            "zap_report_read", "zap_report_read_chunk", "zap_scan_history_list", "zap_scan_history_customer_handoff"
    })
    void operatorSelectedScopesAllowTheConfiguredCrawlAndReportWorkflow(String toolName) {
        runner(nativeAliases(EXPLICIT_SCOPES)).run(context -> {
            List<String> scopes = context.getBean(ApiKeyProperties.class).getApiKeys().getFirst().getScopes();
            ToolAuthorizationService authorization = authorization(context.getBean(ToolAuthorizationProperties.class));
            assertThat(authorization.authorizeToolsList(scopes).allowed()).isTrue();
            assertThat(authorization.authorizeToolCall(scopes, toolName).allowed()).as(toolName).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "zap_attack_start", "zap_active_scan_start", "zap_active_scan_as_user", "zap_queue_active_scan",
            "zap_auth_session_prepare", "zap_auth_session_validate", "zap_target_import", "zap_import_openapi_spec_url",
            "zap_automation_plan_run", "zap_context_upsert", "zap_user_upsert", "zap_scan_policy_rule_set"
    })
    void operatorSelectedScopesDenyActionsOutsideTheGrantEvenOnExpertSurface(String toolName) {
        runner(nativeAliases(EXPLICIT_SCOPES))
                .withPropertyValues("mcp.server.tools.surface=expert")
                .run(context -> {
                    List<String> scopes = context.getBean(ApiKeyProperties.class).getApiKeys().getFirst().getScopes();
                    var decision = authorization(context.getBean(ToolAuthorizationProperties.class))
                            .authorizeToolCall(scopes, toolName);
                    assertThat(decision.mapped()).as(toolName).isTrue();
                    assertThat(decision.allowed()).as(toolName).isFalse();
                    assertThat(decision.missingScopes()).as(toolName).isNotEmpty();
                });
    }

    private Map<String, Object> nativeAliases(String scopes) {
        return Map.of("MCP_SERVER_AUTH_APIKEYS_0_CLIENTID", "restricted-client",
                "MCP_SERVER_AUTH_APIKEYS_0_KEY", "operator-key",
                "MCP_SERVER_AUTH_APIKEYS_0_SCOPES", scopes);
    }

    private ToolAuthorizationService authorization(ToolAuthorizationProperties properties) {
        return new ToolAuthorizationService(new ToolScopeRegistry(), properties);
    }

    private ApplicationContextRunner runner(Map<String, Object> variables) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().replace("systemEnvironment",
                            new SystemEnvironmentPropertySource("systemEnvironment", variables));
                    try {
                        new YamlPropertySourceLoader().load("shipped-application", new ClassPathResource("application.yml"))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withUserConfiguration(BindingConfiguration.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ApiKeyProperties.class, ToolAuthorizationProperties.class})
    static class BindingConfiguration {
    }
}
