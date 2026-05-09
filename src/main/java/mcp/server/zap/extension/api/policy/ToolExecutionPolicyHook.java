package mcp.server.zap.extension.api.policy;

/**
 * Extension API hook for allow, deny, or abstain policy checks on tool execution.
 */
public interface ToolExecutionPolicyHook {

    PolicyEnforcementDecision evaluate(ToolExecutionPolicyContext context);
}
