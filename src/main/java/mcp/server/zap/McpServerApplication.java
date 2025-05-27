package mcp.server.zap;

import mcp.server.zap.service.ActiveScanService;
import mcp.server.zap.service.CoreService;
import mcp.server.zap.service.OpenApiService;
import mcp.server.zap.service.ReportService;
import mcp.server.zap.service.SpiderScanService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider toolCallbacks(CoreService coreService,
                                             ActiveScanService activeScanService,
                                             SpiderScanService spiderScanService,
                                             OpenApiService openApiService,
                                             ReportService reportService) {
        return MethodToolCallbackProvider.builder().toolObjects(
                coreService,
                activeScanService,
                spiderScanService,
                openApiService,
                reportService
        ).build();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
