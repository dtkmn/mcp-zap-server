package mcp.server.zap.core.service.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.core.gateway.GatewayCorePolicyAdapter;
import mcp.gateway.core.policy.ToolPolicyDeniedException;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolExecutionPolicyServiceTest {

    @Test
    void offModeDoesNotBlockToolExecution() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.OFF);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> PolicyEnforcementDecision.deny("deny for test")),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatCode(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-off"))
                .doesNotThrowAnyException();
    }

    @Test
    void dryRunModeRecordsDenialsWithoutBlocking() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.DRY_RUN);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> PolicyEnforcementDecision.deny("deny for test")),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatCode(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-dry"))
                .doesNotThrowAnyException();

        verify(observabilityService).recordPolicyDecision(eq("dry_run_deny"), org.mockito.ArgumentMatchers.anyMap(), eq("corr-dry"));
    }

    @Test
    void enforceModeBlocksDeniedToolExecution() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> PolicyEnforcementDecision.deny("deny for test")),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatThrownBy(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-enforce"))
                .isInstanceOf(ToolPolicyDeniedException.class)
                .hasMessageContaining("Tool execution denied by policy");

        verify(observabilityService).recordPolicyDecision(eq("deny"), org.mockito.ArgumentMatchers.anyMap(), eq("corr-enforce"));
    }

    @Test
    void enforceModeBlocksWhenNoPolicyHookIsConfigured() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatThrownBy(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-no-hook"))
                .isInstanceOf(ToolPolicyDeniedException.class)
                .hasMessageContaining("no_policy_hook_configured");

        Map<String, Object> details = capturedPolicyDetails(observabilityService, "corr-no-hook");
        assertThat(details)
                .containsEntry("reason", "no_policy_hook_configured")
                .containsEntry("policyProviderCount", 0);
    }

    @Test
    void enforceModeBlocksWhenEveryPolicyHookAbstains() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> PolicyEnforcementDecision.abstain(
                        "no_policy_bundle_configured",
                        Map.of("policyProvider", "basic_policy_bundle")
                )),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatThrownBy(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-abstain"))
                .isInstanceOf(ToolPolicyDeniedException.class)
                .hasMessageContaining("no_active_policy_provider_configured");

        Map<String, Object> details = capturedPolicyDetails(observabilityService, "corr-abstain");
        assertThat(details)
                .containsEntry("reason", "no_active_policy_provider_configured")
                .containsEntry("policyProviderCount", 1);
        List<Map<String, Object>> abstainedPolicyProviders = mapList(details, "abstainedPolicyProviders");
        assertThat(abstainedPolicyProviders).hasSize(1);
        assertThat(abstainedPolicyProviders.getFirst())
                .containsEntry("policyProvider", "basic_policy_bundle")
                .containsEntry("reason", "no_policy_bundle_configured");
    }

    @Test
    void enforceModeAllowsWhenOnePolicyHookAllowsAndAnotherAbstains() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(
                        context -> PolicyEnforcementDecision.abstain(
                                "no_policy_bundle_configured",
                                Map.of("policyProvider", "basic_policy_bundle")
                        ),
                        context -> PolicyEnforcementDecision.allow(
                                "allowed by configured provider",
                                Map.of("policyProvider", "custom_policy_provider")
                        )
                ),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatCode(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-allow"))
                .doesNotThrowAnyException();

        Map<String, Object> details = capturedPolicyDetails(observabilityService, "allow", "corr-allow");
        assertThat(details)
                .containsEntry("reason", "allowed by configured provider")
                .containsEntry("policyProvider", "custom_policy_provider")
                .containsEntry("policyProviderCount", 2);
        assertThat(mapList(details, "abstainedPolicyProviders"))
                .hasSize(1);
    }

    @Test
    void extensionPolicyDetailsCannotOverwriteCoreAuditFacts() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.DRY_RUN);
        ObservabilityService observabilityService = mock(ObservabilityService.class);

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> PolicyEnforcementDecision.allow(
                        "core reason wins",
                        Map.of(
                                "tool", "spoofed_tool",
                                "mode", "enforce",
                                "allowed", false,
                                "reason", "spoofed reason",
                                "clientId", "spoofed-client",
                                "workspaceId", "spoofed-workspace",
                                "correlationId", "spoofed-correlation",
                                "outcome", "allow",
                                "policyProvider", "sample"
                        )
                )),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        service.enforce("zap_attack_start", "https://prod.example.com", "corr-spoof");

        Map<String, Object> details = capturedPolicyDetails(observabilityService, "dry_run_allow", "corr-spoof");
        assertThat(details)
                .containsEntry("tool", "zap_attack_start")
                .containsEntry("mode", "dry_run")
                .containsEntry("allowed", true)
                .containsEntry("reason", "core reason wins")
                .containsEntry("policyProvider", "sample");
        assertThat(details)
                .doesNotContainEntry("tool", "spoofed_tool")
                .doesNotContainEntry("allowed", false)
                .doesNotContainKeys("clientId", "workspaceId", "correlationId", "outcome");
        assertThat(objectMap(details, "extensionDetails"))
                .containsEntry("tool", "spoofed_tool")
                .containsEntry("allowed", false)
                .containsEntry("clientId", "spoofed-client")
                .containsEntry("workspaceId", "spoofed-workspace")
                .containsEntry("correlationId", "spoofed-correlation")
                .containsEntry("outcome", "allow");
    }

    @Test
    void extensionPolicyDetailsWithNullKeysDoNotCrashPolicyAudit() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.DRY_RUN);
        ObservabilityService observabilityService = mock(ObservabilityService.class);
        Map<String, Object> detailsWithNullKey = new LinkedHashMap<>();
        detailsWithNullKey.put(null, "ignored");
        detailsWithNullKey.put("policyProvider", "sample");

        ToolExecutionPolicyService service = new ToolExecutionPolicyService(
                properties,
                provider(context -> new PolicyEnforcementDecision(
                        PolicyEnforcementDecision.Outcome.ALLOW,
                        "null key should not crash",
                        detailsWithNullKey
                )),
                observabilityService,
                new GatewayCorePolicyAdapter(),
                clientWorkspaceResolver()
        );

        assertThatCode(() -> service.enforce("zap_attack_start", "https://prod.example.com", "corr-null-key"))
                .doesNotThrowAnyException();

        Map<String, Object> details = capturedPolicyDetails(observabilityService, "dry_run_allow", "corr-null-key");
        assertThat(details)
                .containsEntry("policyProvider", "sample")
                .doesNotContainKey(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPolicyDetails(ObservabilityService observabilityService, String correlationId) {
        return capturedPolicyDetails(observabilityService, "deny", correlationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPolicyDetails(ObservabilityService observabilityService,
                                                      String outcome,
                                                      String correlationId) {
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(observabilityService).recordPolicyDecision(eq(outcome), detailsCaptor.capture(), eq(correlationId));
        return detailsCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Map<String, Object> values, String key) {
        return (List<Map<String, Object>>) values.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }

    private ClientWorkspaceResolver clientWorkspaceResolver() {
        return new ClientWorkspaceResolver(new ApiKeyProperties());
    }

    private ObjectProvider<ToolExecutionPolicyHook> provider(ToolExecutionPolicyHook... hooks) {
        List<ToolExecutionPolicyHook> hookList = List.of(hooks);
        return new ObjectProvider<>() {
            @Override
            public ToolExecutionPolicyHook getObject(Object... args) {
                return hookList.getFirst();
            }

            @Override
            public ToolExecutionPolicyHook getIfAvailable() {
                return hookList.isEmpty() ? null : hookList.getFirst();
            }

            @Override
            public ToolExecutionPolicyHook getIfUnique() {
                return hookList.size() == 1 ? hookList.getFirst() : null;
            }

            @Override
            public ToolExecutionPolicyHook getObject() {
                return hookList.getFirst();
            }

            @Override
            public ToolExecutionPolicyHook getIfAvailable(java.util.function.Supplier<ToolExecutionPolicyHook> defaultSupplier) {
                return hookList.isEmpty() ? defaultSupplier.get() : hookList.getFirst();
            }

            @Override
            public ToolExecutionPolicyHook getIfUnique(java.util.function.Supplier<ToolExecutionPolicyHook> defaultSupplier) {
                return hookList.size() == 1 ? hookList.getFirst() : defaultSupplier.get();
            }

            @Override
            public java.util.Iterator<ToolExecutionPolicyHook> iterator() {
                return hookList.iterator();
            }

            @Override
            public Stream<ToolExecutionPolicyHook> stream() {
                return hookList.stream();
            }

            @Override
            public Stream<ToolExecutionPolicyHook> orderedStream() {
                return hookList.stream();
            }
        };
    }
}
