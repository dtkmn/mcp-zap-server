package mcp.server.zap.core.service;

import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for raw inventory and core browsing tools.
 */
@Service
public class ExpertInventoryMcpToolsService implements ExpertToolGroup {
    private final CoreService coreService;

    public ExpertInventoryMcpToolsService(CoreService coreService) {
        this.coreService = coreService;
    }

    @Tool(name = "zap_alerts", description = "Retrieve alerts for the given base URL")
    public List<String> getAlerts(@ToolParam(description = "baseUrl") String baseUrl) {
        return coreService.getAlerts(baseUrl);
    }

    @Tool(name = "zap_hosts", description = "Retrieve the list of hosts accessed through/by ZAP")
    public List<String> getHosts() {
        return coreService.getHosts();
    }

    @Tool(name = "zap_sites", description = "Retrieve the list of sites accessed through/by ZAP")
    public List<String> getSites() {
        return coreService.getSites();
    }

    @Tool(name = "zap_urls", description = "Retrieve the list of URLs accessed through/by ZAP, optionally filtered by base URL")
    public List<String> getUrls(@ToolParam(required = false, description = "Base URL to filter (optional)") String baseUrl) {
        return coreService.getUrls(baseUrl);
    }
}
