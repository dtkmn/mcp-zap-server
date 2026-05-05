package mcp.server.zap.core.service.policy;

/**
 * Optional provider hook for allow or deny policy checks on tool execution.
 */
public interface ToolExecutionPolicyHook {
    PolicyEnforcementDecision evaluate(ToolExecutionPolicyContext context);
}
