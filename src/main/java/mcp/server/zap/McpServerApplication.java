package mcp.server.zap;

import mcp.server.zap.core.configuration.ToolSurfaceProperties;
import mcp.server.zap.core.service.ExpertToolGroup;
import mcp.server.zap.core.service.GuidedSecurityToolsService;
import mcp.server.zap.core.service.PassiveScanMcpToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.List;

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
                                                     PassiveScanMcpToolsService passiveScanMcpToolsService,
                                                     List<ExpertToolGroup> expertToolGroups) {
        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(guidedSecurityToolsService);
        toolObjects.add(passiveScanMcpToolsService);

        if (toolSurfaceProperties.expert()) {
            toolObjects.addAll(expertToolGroups);
        }

        return MethodToolCallbackProvider.builder().toolObjects(toolObjects.toArray()).build();
    }

}
