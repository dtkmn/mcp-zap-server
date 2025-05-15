package mcp.server.zap;

import mcp.server.zap.service.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public List<ToolCallback> toolCallbacks(CoreService coreService,
                                            ActiveScanService activeScanService,
                                            SpiderScanService spiderScanService,
                                            OpenApiService openApiService,
                                            ReportService reportService) {
        return List.of(ToolCallbacks.from(coreService, activeScanService, spiderScanService, openApiService, reportService));
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
