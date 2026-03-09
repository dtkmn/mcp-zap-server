package mcp.server.zap;

import mcp.server.zap.core.service.ScanJobQueueService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.ContextUserService;
import mcp.server.zap.core.service.CoreService;
import mcp.server.zap.core.service.OpenApiService;
import mcp.server.zap.core.service.ReportService;
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
    public ToolCallbackProvider toolCallbackProvider(CoreService coreService,
                                                     ContextUserService contextUserService,
                                                     AjaxSpiderService ajaxSpiderService,
                                                     ScanJobQueueService scanJobQueueService,
                                                     OpenApiService openApiService,
                                                     ReportService reportService) {
        return MethodToolCallbackProvider.builder().toolObjects(
                coreService,
                contextUserService,
                ajaxSpiderService,
                scanJobQueueService,
                openApiService,
                reportService
        ).build();
    }

}
