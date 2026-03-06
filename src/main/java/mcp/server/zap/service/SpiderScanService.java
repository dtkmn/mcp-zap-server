package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Internal service for managing ZAP spider scans.
 * Queue orchestration calls these methods to start, monitor, and stop scans.
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

    String startSpiderScanJob(String targetUrl) {
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
            return scanId;
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

    String startSpiderScanAsUserJob(String contextId,
                                    String userId,
                                    String targetUrl,
                                    String maxChildren,
                                    String recurse,
                                    String subtreeOnly) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");
        urlValidationService.validateUrl(targetUrl);

        String effectiveMaxChildren = hasText(maxChildren) ? maxChildren.trim() : String.valueOf(scanLimitProperties.getSpiderMaxDepth());
        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectiveSubtreeOnly = hasText(subtreeOnly) ? subtreeOnly.trim() : "false";

        try {
            zap.spider.setOptionThreadCount(scanLimitProperties.getSpiderThreadCount());
            zap.spider.setOptionMaxDuration(scanLimitProperties.getMaxSpiderScanDurationInMins());

            ApiResponse resp = zap.spider.scanAsUser(
                    normalizedContextId,
                    normalizedUserId,
                    targetUrl,
                    effectiveMaxChildren,
                    effectiveRecurse,
                    effectiveSubtreeOnly
            );

            String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
            log.info("Spider-as-user started with ID: {} for URL: {}, context: {}, user: {}",
                    scanId, targetUrl, normalizedContextId, normalizedUserId);
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error launching spider-as-user for URL {}: {}", targetUrl, e.getMessage(), e);
            throw new ZapApiException("Error launching spider-as-user for URL " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    int getSpiderScanProgressPercent(String scanId) {
        try {
            ApiResponse resp = zap.spider.status(scanId);
            return Integer.parseInt(((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue());
        } catch (ClientApiException e) {
            log.error("Error retrieving spider status for ID {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving spider status for ID " + scanId, e);
        }
    }

    void stopSpiderScanJob(String scanId) {
        try {
            zap.spider.stop(scanId);
        } catch (ClientApiException e) {
            log.error("Error stopping spider scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping spider scan " + scanId, e);
        }
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

}
