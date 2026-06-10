package mcp.server.zap.core.service.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.context.GatewayExecutionContext;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.policy.ToolPolicyDecision;
import mcp.gateway.core.policy.ToolPolicyDeniedException;
import mcp.gateway.core.policy.ToolPolicyEvaluationContext;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.core.gateway.GatewayCorePolicyAdapter;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
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
            "clientId",
            "workspaceId",
            "correlationId",
            "outcome",
            "extensionDetails"
    );

    private final PolicyEnforcementProperties policyEnforcementProperties;
    private final ObjectProvider<ToolExecutionPolicyHook> toolExecutionPolicyHookProvider;
    private final ObservabilityService observabilityService;
    private final GatewayCorePolicyAdapter gatewayCorePolicyAdapter;
    private final ClientWorkspaceResolver clientWorkspaceResolver;

    public ToolExecutionPolicyService(PolicyEnforcementProperties policyEnforcementProperties,
                                      ObjectProvider<ToolExecutionPolicyHook> toolExecutionPolicyHookProvider,
                                      ObservabilityService observabilityService,
                                      GatewayCorePolicyAdapter gatewayCorePolicyAdapter,
                                      ClientWorkspaceResolver clientWorkspaceResolver) {
        this.policyEnforcementProperties = policyEnforcementProperties;
        this.toolExecutionPolicyHookProvider = toolExecutionPolicyHookProvider;
        this.observabilityService = observabilityService;
        this.gatewayCorePolicyAdapter = gatewayCorePolicyAdapter;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    public void enforce(String toolName, String target, String correlationId) {
        PolicyEnforcementProperties.Mode mode = policyEnforcementProperties.getMode();
        if (mode == PolicyEnforcementProperties.Mode.OFF) {
            return;
        }

        GatewayExecutionContext executionContext =
                clientWorkspaceResolver.resolveCurrentExecutionContext(correlationId);
        GatewayToolExecutionContext toolContext = GatewayToolExecutionContext.of(
                executionContext,
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_CALL, toolName),
                target
        );
        ToolPolicyEvaluationContext context = gatewayCorePolicyAdapter.evaluationContext(toolContext);
        ToolPolicyDecision decision = evaluatePolicyHooks(context);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", context.toolName());
        if (context.target() != null) {
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
                    "Tool execution denied by policy for " + context.toolName() + ": " + decision.reason()
            );
        }
    }

    private ToolPolicyDecision evaluatePolicyHooks(ToolPolicyEvaluationContext context) {
        List<ToolExecutionPolicyHook> hooks = toolExecutionPolicyHookProvider.orderedStream().toList();
        if (hooks.isEmpty()) {
            return gatewayCorePolicyAdapter.noPolicyHookDecision();
        }

        List<Map<String, Object>> abstentions = new ArrayList<>();
        ToolPolicyDecision firstAllow = null;
        for (int index = 0; index < hooks.size(); index++) {
            ToolPolicyDecision decision =
                    gatewayCorePolicyAdapter.evaluateExtensionHook(hooks.get(index), context, hooks.size(), index);
            if (decision.abstained()) {
                abstentions.add(gatewayCorePolicyAdapter.abstentionDetails(decision));
                continue;
            }

            if (decision.denied()) {
                return gatewayCorePolicyAdapter.withRuntimeDetails(decision, hooks.size(), abstentions);
            }

            if (firstAllow == null) {
                firstAllow = decision;
            }
        }

        if (firstAllow != null) {
            return gatewayCorePolicyAdapter.withRuntimeDetails(firstAllow, hooks.size(), abstentions);
        }

        return gatewayCorePolicyAdapter.noActiveProviderDecision(hooks.size(), abstentions);
    }

    private void addExtensionDetails(Map<String, Object> auditDetails, Map<String, Object> policyDetails) {
        if (policyDetails == null || policyDetails.isEmpty()) {
            return;
        }

        Map<String, Object> reservedDetails = new LinkedHashMap<>();
        policyDetails.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if ("extensionDetails".equals(key)) {
                mergeExtensionDetails(reservedDetails, value);
                return;
            }
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

    private void mergeExtensionDetails(Map<String, Object> reservedDetails, Object value) {
        if (value instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, nestedValue) -> {
                if (key != null && nestedValue != null) {
                    reservedDetails.put(key.toString(), nestedValue);
                }
            });
        } else if (value != null) {
            reservedDetails.put("value", value);
        }
    }

}
