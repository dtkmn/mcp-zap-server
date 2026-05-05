package mcp.server.zap.core.service;

import java.util.Map;
import mcp.server.zap.core.service.policy.PolicyDryRunService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for policy bundle preview and debugging.
 */
@Service
public class ExpertPolicyMcpToolsService implements ExpertToolGroup {
    private final PolicyDryRunService policyDryRunService;

    public ExpertPolicyMcpToolsService(PolicyDryRunService policyDryRunService) {
        this.policyDryRunService = policyDryRunService;
    }

    @Tool(
            name = "zap_policy_dry_run",
            description = "Dry-run a Policy Bundle v1 document against an exact MCP tool action, target host or URL, and evaluation time so rollout teams can preview allow or deny outcomes before enforcement."
    )
    public Map<String, Object> dryRunPolicy(
            @ToolParam(description = "Policy Bundle v1 JSON document.") String policyBundle,
            @ToolParam(description = "Exact MCP tool or action such as zap_attack_start or mcp:tools:list.") String toolName,
            @ToolParam(required = false, description = "Target hostname or absolute URL to normalize for host-scoped rules. Optional.") String target,
            @ToolParam(required = false, description = "ISO-8601 instant or offset datetime to evaluate in the bundle timezone. Optional; defaults to now.") String evaluatedAt
    ) {
        return policyDryRunService.dryRun(policyBundle, toolName, target, evaluatedAt);
    }
}
