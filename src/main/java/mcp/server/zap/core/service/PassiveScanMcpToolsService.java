package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP adapter for passive scan tools.
 */
@Service
public class PassiveScanMcpToolsService {
    private final PassiveScanService passiveScanService;

    public PassiveScanMcpToolsService(PassiveScanService passiveScanService) {
        this.passiveScanService = passiveScanService;
    }

    @Tool(
            name = "zap_passive_scan_status",
            description = "Get passive scan backlog and completion status."
    )
    public String getPassiveScanStatus() {
        return passiveScanService.getPassiveScanStatus();
    }

    @Tool(
            name = "zap_passive_scan_wait",
            description = "Wait for the passive scan backlog to drain before reading findings or generating reports."
    )
    public String waitForPassiveScanCompletion(
            @ToolParam(description = "Maximum seconds to wait before returning (optional, default: 60)") Integer timeoutSeconds,
            @ToolParam(description = "Polling interval in milliseconds (optional, default: 1000)") Integer pollIntervalMs
    ) {
        return passiveScanService.waitForPassiveScanCompletion(timeoutSeconds, pollIntervalMs);
    }
}
