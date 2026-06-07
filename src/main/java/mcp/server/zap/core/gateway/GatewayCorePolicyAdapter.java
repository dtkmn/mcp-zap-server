package mcp.server.zap.core.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.policy.ToolPolicyDecision;
import mcp.gateway.core.policy.ToolPolicyEvaluationContext;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyContext;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;
import org.springframework.stereotype.Component;

/**
 * Adapts security-pack policy hooks to generic gateway-core policy contracts.
 */
@Component
public class GatewayCorePolicyAdapter {
    private static final String EXTENSION_DETAILS_KEY = "extensionDetails";
    private static final Set<String> RUNTIME_DETAIL_KEYS = Set.of(
            "policyProviderCount",
            "abstainedPolicyProviders"
    );

    public ToolPolicyEvaluationContext evaluationContext(String toolName, String target, String correlationId) {
        return evaluationContext(GatewayToolExecutionContext.of(
                null,
                null,
                correlationId,
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_CALL, toolName),
                target
        ));
    }

    public ToolPolicyEvaluationContext evaluationContext(GatewayToolExecutionContext context) {
        return ToolPolicyEvaluationContext.from(context);
    }

    public ToolExecutionPolicyContext extensionContext(ToolPolicyEvaluationContext context) {
        return new ToolExecutionPolicyContext(
                context.toolName(),
                context.target(),
                context.correlationId()
        );
    }

    public ToolPolicyDecision evaluateExtensionHook(ToolExecutionPolicyHook hook,
                                                    ToolPolicyEvaluationContext context,
                                                    int policyProviderCount,
                                                    int policyHookIndex) {
        PolicyEnforcementDecision extensionDecision;
        try {
            extensionDecision = hook.evaluate(extensionContext(context));
        } catch (RuntimeException e) {
            return hookErrorDecision(policyProviderCount, policyHookIndex, e);
        }

        if (extensionDecision == null || extensionDecision.outcome() == null) {
            return invalidHookDecision(policyProviderCount, policyHookIndex);
        }
        return fromExtensionDecision(extensionDecision);
    }

    public ToolPolicyDecision fromExtensionDecision(PolicyEnforcementDecision decision) {
        if (decision.allowed()) {
            return ToolPolicyDecision.allow(decision.reason(), decision.details());
        }
        if (decision.denied()) {
            return ToolPolicyDecision.deny(decision.reason(), decision.details());
        }
        return ToolPolicyDecision.abstain(decision.reason(), decision.details());
    }

    public ToolPolicyDecision noPolicyHookDecision() {
        return ToolPolicyDecision.deny("no_policy_hook_configured", Map.of("policyProviderCount", 0));
    }

    public ToolPolicyDecision hookErrorDecision(int policyProviderCount, int policyHookIndex, RuntimeException error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProviderCount", policyProviderCount);
        details.put("policyHookIndex", policyHookIndex);
        details.put("errorClass", error.getClass().getSimpleName());
        return ToolPolicyDecision.deny("policy_hook_error", details);
    }

    public ToolPolicyDecision invalidHookDecision(int policyProviderCount, int policyHookIndex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProviderCount", policyProviderCount);
        details.put("policyHookIndex", policyHookIndex);
        return ToolPolicyDecision.deny("policy_hook_returned_invalid_decision", details);
    }

    public ToolPolicyDecision noActiveProviderDecision(int policyProviderCount,
                                                       List<Map<String, Object>> abstentions) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProviderCount", policyProviderCount);
        if (abstentions != null && !abstentions.isEmpty()) {
            details.put("abstainedPolicyProviders", abstentions);
        }
        return ToolPolicyDecision.deny("no_active_policy_provider_configured", details);
    }

    public ToolPolicyDecision withRuntimeDetails(ToolPolicyDecision decision,
                                                 int policyProviderCount,
                                                 List<Map<String, Object>> abstentions) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> extensionDetails = new LinkedHashMap<>();
        if (decision.details() != null && !decision.details().isEmpty()) {
            decision.details().forEach((key, value) -> addProviderDetail(details, extensionDetails, key, value));
        }
        details.put("policyProviderCount", policyProviderCount);
        if (abstentions != null && !abstentions.isEmpty()) {
            details.put("abstainedPolicyProviders", abstentions);
        }
        if (!extensionDetails.isEmpty()) {
            details.put(EXTENSION_DETAILS_KEY, extensionDetails);
        }

        if (decision.allowed()) {
            return ToolPolicyDecision.allow(decision.reason(), details);
        }
        if (decision.abstained()) {
            return ToolPolicyDecision.abstain(decision.reason(), details);
        }
        return ToolPolicyDecision.deny(decision.reason(), details);
    }

    public Map<String, Object> abstentionDetails(ToolPolicyDecision decision) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (decision.details() != null && !decision.details().isEmpty()) {
            details.putAll(decision.details());
        }
        if (decision.reason() != null) {
            details.put("reason", decision.reason());
        }
        return details;
    }

    private void addProviderDetail(Map<String, Object> details,
                                   Map<String, Object> extensionDetails,
                                   String key,
                                   Object value) {
        if (key == null || value == null) {
            return;
        }
        if (RUNTIME_DETAIL_KEYS.contains(key)) {
            extensionDetails.put(key, value);
            return;
        }
        if (EXTENSION_DETAILS_KEY.equals(key)) {
            mergeExtensionDetails(extensionDetails, value);
            return;
        }
        details.put(key, value);
    }

    private void mergeExtensionDetails(Map<String, Object> extensionDetails, Object value) {
        if (value instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, nestedValue) -> {
                if (key != null && nestedValue != null) {
                    extensionDetails.put(key.toString(), nestedValue);
                }
            });
        } else if (value != null) {
            extensionDetails.put("value", value);
        }
    }
}
