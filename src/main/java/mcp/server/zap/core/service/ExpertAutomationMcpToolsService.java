package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for automation framework execution tools.
 */
@Service
public class ExpertAutomationMcpToolsService implements ExpertToolGroup {
    private final AutomationPlanService automationPlanService;

    public ExpertAutomationMcpToolsService(AutomationPlanService automationPlanService) {
        this.automationPlanService = automationPlanService;
    }

    @Tool(
            name = "zap_automation_plan_run",
            description = "Run a ZAP Automation Framework plan from an existing file path or inline YAML content."
    )
    public String runAutomationPlan(
            @ToolParam(required = false, description = "Existing plan file path under the automation workspace (optional; provide either this or planYaml)") String planPath,
            @ToolParam(required = false, description = "Inline automation plan YAML content to materialize and run (optional; provide either this or planPath)") String planYaml,
            @ToolParam(required = false, description = "Optional file name to use when planYaml is provided (default: automation-plan.yaml)") String planFileName
    ) {
        return automationPlanService.runAutomationPlan(planPath, planYaml, planFileName);
    }

    @Tool(
            name = "zap_automation_plan_status",
            description = "Get status for a previously launched ZAP Automation Framework plan."
    )
    public String getAutomationPlanStatus(
            @ToolParam(description = "Automation plan ID returned by zap_automation_plan_run") String planId,
            @ToolParam(required = false, description = "Optional maximum number of info/warn/error messages to render (default: 20, max: 100)") Integer maxMessages
    ) {
        return automationPlanService.getAutomationPlanStatus(planId, maxMessages);
    }

    @Tool(
            name = "zap_automation_plan_artifacts",
            description = "List and preview files produced by a plan run under the automation workspace."
    )
    public String getAutomationPlanArtifacts(
            @ToolParam(description = "Normalized plan file path returned by zap_automation_plan_run. Relative paths resolve under the automation workspace.") String planPath,
            @ToolParam(required = false, description = "Optional maximum number of discovered artifact files to render (default: 20, max: 50)") Integer maxArtifacts,
            @ToolParam(required = false, description = "Optional total preview character budget across text artifacts (default: 8000, max: 50000)") Integer maxChars
    ) {
        return automationPlanService.getAutomationPlanArtifacts(planPath, maxArtifacts, maxChars);
    }
}
