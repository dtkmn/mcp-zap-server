package mcp.server.zap;

import java.util.ArrayList;
import java.util.List;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.GuidedAuthSessionMcpToolsService;
import mcp.server.zap.core.service.ExpertToolGroup;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import mcp.server.zap.core.service.ScanHistoryMcpToolsService;
import mcp.server.zap.core.configuration.ToolSurfaceProperties;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ToolSurfaceProperties.class)
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(ToolSurfaceProperties toolSurfaceProperties,
                                                     GuidedSecurityToolsService guidedSecurityToolsService,
                                                     GuidedAuthSessionMcpToolsService guidedAuthSessionMcpToolsService,
                                                     PassiveScanMcpToolsService passiveScanMcpToolsService,
                                                     ScanHistoryMcpToolsService scanHistoryMcpToolsService,
                                                     List<ExpertToolGroup> expertToolGroups) {
        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(guidedSecurityToolsService);
        toolObjects.add(guidedAuthSessionMcpToolsService);
        toolObjects.add(passiveScanMcpToolsService);
        toolObjects.add(scanHistoryMcpToolsService);

        if (toolSurfaceProperties.expert()) {
            toolObjects.addAll(expertToolGroups);
        }

        return MethodToolCallbackProvider.builder().toolObjects(toolObjects.toArray()).build();
    }

}
