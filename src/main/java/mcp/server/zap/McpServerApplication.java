package mcp.server.zap;

import java.util.ArrayList;
import java.util.List;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.GuidedAuthSessionMcpToolsService;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import mcp.server.zap.core.service.ScanHistoryMcpToolsService;
import mcp.server.zap.core.service.AddonMaintenanceMcpToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(GuidedSecurityToolsService guidedSecurityToolsService,
                                                     GuidedAuthSessionMcpToolsService guidedAuthSessionMcpToolsService,
                                                     PassiveScanMcpToolsService passiveScanMcpToolsService,
                                                     ScanHistoryMcpToolsService scanHistoryMcpToolsService,
                                                     AddonMaintenanceMcpToolsService addonMaintenanceMcpToolsService) {
        List<Object> tools = new ArrayList<>(List.of(guidedSecurityToolsService, guidedAuthSessionMcpToolsService,
                passiveScanMcpToolsService, scanHistoryMcpToolsService, addonMaintenanceMcpToolsService));
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
    }

}
