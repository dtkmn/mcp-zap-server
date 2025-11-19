package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Service for managing ZAP Spider Scans.
 * This service provides methods to start and check the status of spider scans using the ZAP API.
 */
@Slf4j
@Service
public class SpiderScanService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;

    public SpiderScanService(ClientApi zap, 
                            UrlValidationService urlValidationService,
                            ScanLimitProperties scanLimitProperties) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

    /**
     * Start a spider scan against the given URL and return the scanId.
     *
     * @param targetUrl Target URL to scan
     * @return Scan ID of the started spider scan
     */
    @Tool(name = "zap_spider", description = "Start a spider scan on the given URL")
    public String startSpider(@ToolParam(description = "Target URL to spider scan (e.g., http://example.com)") String targetUrl) {
        // Validate URL before scanning
        urlValidationService.validateUrl(targetUrl);

        try {
            // Force-fetch the root so it appears in the tree (retry with delay if fails)
            int maxRetries = 3;
            for (int i = 0; i < maxRetries; i++) {
                try {
                    zap.core.accessUrl(targetUrl, "true");
                    break; // Success
                } catch (ClientApiException e) {
                    if (i == maxRetries - 1) {
                        log.error("Failed to access URL after {} retries: {}", maxRetries, e.getMessage());
                        throw new ZapApiException("Target website is blocking ZAP requests or is unreachable. " +
                            "This could be due to WAF protection, IP blocking, or network issues. " +
                            "Original error: " + e.getMessage(), e);
                    }
                    log.warn("Retry {}/{} - Failed to access URL {}: {}", i + 1, maxRetries, targetUrl, e.getMessage());
                    Thread.sleep(2000); // Wait 2 seconds before retry
                }
            }
            
            // Set spider options from configuration
            zap.spider.setOptionThreadCount(scanLimitProperties.getSpiderThreadCount());
            zap.spider.setOptionMaxDuration(scanLimitProperties.getMaxSpiderScanDurationInMins());
            
            ApiResponse resp = zap.spider.scan(
                targetUrl, 
                String.valueOf(scanLimitProperties.getSpiderMaxDepth()), 
                "true", 
                "", 
                "false"
            );
            String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
            
            log.info("Spider scan started with ID: {} for URL: {}", scanId, targetUrl);
            return "Spider scan started with ID: " + scanId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Spider scan was interrupted for URL {}", targetUrl);
            throw new ZapApiException("Spider scan was interrupted", e);
        } catch (ClientApiException e) {
            log.error("Error launching ZAP Spider for URL {}: {}", targetUrl, e.getMessage(), e);
            
            // Provide user-friendly error message for common issues
            String errorMsg = e.getMessage().toLowerCase();
            if (errorMsg.contains("unexpected end of file") || errorMsg.contains("socketexception")) {
                throw new ZapApiException("Target website is blocking ZAP requests or closed the connection. " +
                    "Common causes: WAF protection, bot detection, IP blocking. " +
                    "Try testing with juice-shop (http://juice-shop:3000) or petstore (http://petstore:8080) instead. " +
                    "Original error: " + e.getMessage(), e);
            }
            
            throw new ZapApiException("Error launching ZAP Spider for URL " + targetUrl + ": " + e.getMessage(), e);
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
        } catch (ClientApiException e) {
            log.error("Error retrieving spider status for ID {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving spider status for ID " + scanId, e);
        }
    }

}
