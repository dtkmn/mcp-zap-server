package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ClientApi;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Service for managing ZAP Spider Scans.
 * This service provides methods to start and check the status of spider scans using the ZAP API.
 */
@Slf4j
@Service
public class SpiderScanService {

    private final ClientApi zap;

    public SpiderScanService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * Start a spider scan against the given URL and return the scanId.
     *
     * @param targetUrl Target URL to scan
     * @return Scan ID of the started spider scan
     */
    @Tool(name = "zap_spider", description = "Start a spider scan on the given URL")
    public String startSpider(@ToolParam(description = "targetUrl") String targetUrl) {
        try {
            new URL(targetUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + targetUrl);
        }

        try {

            String sessionName = "scan-" + System.currentTimeMillis();
//        zap.network.setConnectionTimeout("60");
            zap.core.setOptionTimeoutInSecs(60);
            // Force-fetch the root so it appears in the tree
            zap.core.accessUrl(targetUrl, "true");
            // Set spider options
            zap.spider.setOptionThreadCount(5); // Number of threads
            ApiResponse resp = zap.spider.scan(targetUrl, "10", "true", "", "false");
            String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
            return "Spider scan started with ID: " + scanId;
        } catch (Exception e) {
            log.error("Error launching ZAP Spider for URL {}: {}", targetUrl, e.getMessage(), e);
            return "❌ Error launching spider: " + e.getMessage();
        }
    }

    /**
     * Get the status of a spider scan by ID.
     *
     * @param scanId The ID of the spider scan
     * @return Status of the spider scan
     */
    @Tool(name = "zap_spider_status", description = "Get status of a spider scan by ID")
    public String getSpiderStatus(@ToolParam(description = "scanId") String scanId) {
        try {
            ApiResponse resp = zap.spider.status(scanId);
            return ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
        } catch (Exception e) {
            log.error("Error retrieving spider status for ID {}: {}", scanId, e.getMessage(), e);
            return "❌ Error retrieving spider status: " + e.getMessage();
        }
    }

}
