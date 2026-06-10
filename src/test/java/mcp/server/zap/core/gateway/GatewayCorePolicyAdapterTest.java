package mcp.server.zap.core.gateway;

import java.util.List;
import java.util.Map;
import mcp.gateway.core.policy.ToolPolicyDecision;
import mcp.gateway.core.policy.ToolPolicyEvaluationContext;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCorePolicyAdapterTest {
    private final GatewayCorePolicyAdapter adapter = new GatewayCorePolicyAdapter();

    @Test
    void adaptsRuntimeInputsIntoGatewayCorePolicyContext() {
        ToolPolicyEvaluationContext context =
                adapter.evaluationContext(" zap_attack_start ", " https://example.com ", " corr-1 ");

        assertThat(context.toolName()).isEqualTo("zap_attack_start");
        assertThat(context.target()).isEqualTo("https://example.com");
        assertThat(context.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void adaptsGatewayCorePolicyContextIntoExtensionHookContext() {
        ToolExecutionPolicyContext context = adapter.extensionContext(
                new ToolPolicyEvaluationContext("tool", "target", "corr")
        );

        assertThat(context.toolName()).isEqualTo("tool");
        assertThat(context.target()).isEqualTo("target");
        assertThat(context.correlationId()).isEqualTo("corr");
    }

    @Test
    void mapsExtensionDecisionsIntoGatewayCoreDecisions() {
        assertThat(adapter.fromExtensionDecision(PolicyEnforcementDecision.allow("ok")).allowed()).isTrue();
        assertThat(adapter.fromExtensionDecision(PolicyEnforcementDecision.deny("blocked")).denied()).isTrue();
        assertThat(adapter.fromExtensionDecision(PolicyEnforcementDecision.abstain("not configured")).abstained())
                .isTrue();
    }

    @Test
    void evaluatesExtensionHookBehindAdapterBoundary() {
        ToolPolicyEvaluationContext context = new ToolPolicyEvaluationContext("tool", "target", "corr");

        ToolPolicyDecision decision = adapter.evaluateExtensionHook(
                extensionContext -> PolicyEnforcementDecision.allow(
                        "allowed",
                        Map.of(
                                "tool", extensionContext.toolName(),
                                "target", extensionContext.target(),
                                "correlationId", extensionContext.correlationId()
                        )
                ),
                context,
                1,
                0
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.details())
                .containsEntry("tool", "tool")
                .containsEntry("target", "target")
                .containsEntry("correlationId", "corr");
    }

    @Test
    void createsFailClosedRuntimeDecisionsForMissingInvalidAndFailedHooks() {
        assertThat(adapter.noPolicyHookDecision())
                .returns(true, ToolPolicyDecision::denied)
                .extracting(ToolPolicyDecision::reason)
                .isEqualTo("no_policy_hook_configured");

        assertThat(adapter.invalidHookDecision(2, 1))
                .returns(true, ToolPolicyDecision::denied)
                .extracting(ToolPolicyDecision::details)
                .isEqualTo(Map.of("policyProviderCount", 2, "policyHookIndex", 1));

        ToolPolicyDecision hookError = adapter.hookErrorDecision(3, 2, new IllegalStateException("bad"));
        assertThat(hookError.denied()).isTrue();
        assertThat(hookError.reason()).isEqualTo("policy_hook_error");
        assertThat(hookError.details())
                .containsEntry("policyProviderCount", 3)
                .containsEntry("policyHookIndex", 2)
                .containsEntry("errorClass", "IllegalStateException");
    }

    @Test
    void addsRuntimeProviderDetailsWithoutChangingDecisionOutcome() {
        ToolPolicyDecision decision = adapter.withRuntimeDetails(
                ToolPolicyDecision.allow("allowed", Map.of("policyProvider", "test")),
                2,
                List.of(Map.of("reason", "abstained"))
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.details())
                .containsEntry("policyProvider", "test")
                .containsEntry("policyProviderCount", 2)
                .containsEntry("abstainedPolicyProviders", List.of(Map.of("reason", "abstained")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerDetailsCannotSpoofRuntimeOwnedMetadata() {
        ToolPolicyDecision decision = adapter.withRuntimeDetails(
                ToolPolicyDecision.allow("allowed", Map.of(
                        "policyProvider", "test",
                        "policyProviderCount", 999,
                        "abstainedPolicyProviders", List.of(Map.of("spoofed", true)),
                        "extensionDetails", Map.of("source", "provider")
                )),
                2,
                List.of()
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.details())
                .containsEntry("policyProvider", "test")
                .containsEntry("policyProviderCount", 2)
                .doesNotContainKey("abstainedPolicyProviders");
        assertThat((Map<String, Object>) decision.details().get("extensionDetails"))
                .containsEntry("policyProviderCount", 999)
                .containsEntry("abstainedPolicyProviders", List.of(Map.of("spoofed", true)))
                .containsEntry("source", "provider");
    }
}
