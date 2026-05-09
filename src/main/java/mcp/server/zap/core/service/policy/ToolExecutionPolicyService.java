package mcp.server.zap.core.service.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.core.exception.ToolPolicyDeniedException;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyContext;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Shared policy hook path in core that optional policy providers can plug into.
 */
@Service
public class ToolExecutionPolicyService {
    private static final Set<String> CORE_AUDIT_DETAIL_KEYS = Set.of(
            "tool",
            "targetProvided",
            "mode",
            "allowed",
            "reason",
            "extensionDetails"
    );

    private final PolicyEnforcementProperties policyEnforcementProperties;
    private final ObjectProvider<ToolExecutionPolicyHook> toolExecutionPolicyHookProvider;
    private final ObservabilityService observabilityService;

    public ToolExecutionPolicyService(PolicyEnforcementProperties policyEnforcementProperties,
                                      ObjectProvider<ToolExecutionPolicyHook> toolExecutionPolicyHookProvider,
                                      ObservabilityService observabilityService) {
        this.policyEnforcementProperties = policyEnforcementProperties;
        this.toolExecutionPolicyHookProvider = toolExecutionPolicyHookProvider;
        this.observabilityService = observabilityService;
    }

    public void enforce(String toolName, String target, String correlationId) {
        PolicyEnforcementProperties.Mode mode = policyEnforcementProperties.getMode();
        if (mode == PolicyEnforcementProperties.Mode.OFF) {
            return;
        }

        ToolExecutionPolicyContext context = new ToolExecutionPolicyContext(toolName, target, correlationId);
        PolicyEnforcementDecision decision = evaluatePolicyHooks(context);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", toolName);
        if (target != null && !target.isBlank()) {
            details.put("targetProvided", true);
        }
        details.put("mode", mode.name().toLowerCase());
        details.put("allowed", decision.allowed());
        details.put("reason", decision.reason());
        addExtensionDetails(details, decision.details());

        String outcome = decision.allowed()
                ? (mode == PolicyEnforcementProperties.Mode.DRY_RUN ? "dry_run_allow" : "allow")
                : (mode == PolicyEnforcementProperties.Mode.DRY_RUN ? "dry_run_deny" : "deny");
        observabilityService.recordPolicyDecision(outcome, details, correlationId);

        if (mode == PolicyEnforcementProperties.Mode.ENFORCE && !decision.allowed()) {
            throw new ToolPolicyDeniedException(
                    "Tool execution denied by policy for " + toolName + ": " + decision.reason()
            );
        }
    }

    private PolicyEnforcementDecision evaluatePolicyHooks(ToolExecutionPolicyContext context) {
        List<ToolExecutionPolicyHook> hooks = toolExecutionPolicyHookProvider.orderedStream().toList();
        if (hooks.isEmpty()) {
            return PolicyEnforcementDecision.deny("no_policy_hook_configured", Map.of("policyProviderCount", 0));
        }

        List<Map<String, Object>> abstentions = new ArrayList<>();
        PolicyEnforcementDecision firstAllow = null;
        for (int index = 0; index < hooks.size(); index++) {
            PolicyEnforcementDecision decision;
            try {
                decision = hooks.get(index).evaluate(context);
            } catch (RuntimeException e) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("policyProviderCount", hooks.size());
                details.put("policyHookIndex", index);
                details.put("errorClass", e.getClass().getSimpleName());
                return PolicyEnforcementDecision.deny("policy_hook_error", details);
            }

            if (decision == null || decision.outcome() == null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("policyProviderCount", hooks.size());
                details.put("policyHookIndex", index);
                return PolicyEnforcementDecision.deny("policy_hook_returned_invalid_decision", details);
            }

            if (decision.abstained()) {
                abstentions.add(abstentionDetails(decision));
                continue;
            }

            if (decision.denied()) {
                return withRuntimeDetails(decision, hooks.size(), abstentions);
            }

            if (firstAllow == null) {
                firstAllow = decision;
            }
        }

        if (firstAllow != null) {
            return withRuntimeDetails(firstAllow, hooks.size(), abstentions);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProviderCount", hooks.size());
        if (!abstentions.isEmpty()) {
            details.put("abstainedPolicyProviders", abstentions);
        }
        return PolicyEnforcementDecision.deny("no_active_policy_provider_configured", details);
    }

    private PolicyEnforcementDecision withRuntimeDetails(PolicyEnforcementDecision decision,
                                                         int policyProviderCount,
                                                         List<Map<String, Object>> abstentions) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (decision.details() != null && !decision.details().isEmpty()) {
            details.putAll(decision.details());
        }
        details.put("policyProviderCount", policyProviderCount);
        if (!abstentions.isEmpty()) {
            details.put("abstainedPolicyProviders", abstentions);
        }

        if (decision.allowed()) {
            return PolicyEnforcementDecision.allow(decision.reason(), details);
        }
        return PolicyEnforcementDecision.deny(decision.reason(), details);
    }

    private void addExtensionDetails(Map<String, Object> auditDetails, Map<String, Object> policyDetails) {
        if (policyDetails == null || policyDetails.isEmpty()) {
            return;
        }

        Map<String, Object> reservedDetails = new LinkedHashMap<>();
        policyDetails.forEach((key, value) -> {
            if (CORE_AUDIT_DETAIL_KEYS.contains(key) || auditDetails.containsKey(key)) {
                reservedDetails.put(key, value);
            } else {
                auditDetails.put(key, value);
            }
        });
        if (!reservedDetails.isEmpty()) {
            auditDetails.put("extensionDetails", reservedDetails);
        }
    }

    private Map<String, Object> abstentionDetails(PolicyEnforcementDecision decision) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (decision.details() != null && !decision.details().isEmpty()) {
            details.putAll(decision.details());
        }
        if (decision.reason() != null) {
            details.put("reason", decision.reason());
        }
        return details;
    }
}
