package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Service for direct and queue-managed spider scan operations.
 */
@Slf4j
@Service
public class SpiderScanService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;

    /**
     * Build-time dependency injection constructor.
     */
    public SpiderScanService(ClientApi zap, 
                            UrlValidationService urlValidationService,
                            ScanLimitProperties scanLimitProperties) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    public String startSpiderScan(String targetUrl) {
        String scanId = startSpiderScanJob(targetUrl);
        trackDirectScanStarted(scanId, resolveWorkspaceId());
        return formatDirectStartMessage(scanId, targetUrl);
    }

    public String startSpiderScanAsUser(
            String contextId,
            String userId,
            String targetUrl,
            String maxChildren,
            String recurse,
            String subtreeOnly
    ) {
        String effectiveMaxChildren = hasText(maxChildren) ? maxChildren.trim() : String.valueOf(scanLimitProperties.getSpiderMaxDepth());
        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectiveSubtreeOnly = hasText(subtreeOnly) ? subtreeOnly.trim() : "false";
        String scanId = startSpiderScanAsUserJob(contextId, userId, targetUrl, effectiveMaxChildren, effectiveRecurse, effectiveSubtreeOnly);
        trackDirectScanStarted(scanId, resolveWorkspaceId());
        return formatDirectAuthenticatedStartMessage(scanId, contextId, userId, targetUrl, effectiveMaxChildren, effectiveRecurse, effectiveSubtreeOnly);
    }

    public String getSpiderScanStatus(String scanId) {
        String normalizedScanId = requireText(scanId, "scanId");
        int progress = getSpiderScanProgressPercent(normalizedScanId);
        boolean completed = progress >= 100;
        trackDirectScanStatus(normalizedScanId, completed);
        return String.format(
                "Direct spider scan status:%n" +
                        "Scan ID: %s%n" +
                        "Progress: %d%%%n" +
                        "Completed: %s%n" +
                        "%s",
                normalizedScanId,
                progress,
                completed ? "yes" : "no",
                completed
                        ? "Run 'zap_passive_scan_wait' before reading findings or generating reports."
                        : "Use 'zap_spider_stop' to stop this direct crawl, or 'zap_queue_spider_scan' for durable queued execution next time."
        );
    }

    public String stopSpiderScan(String scanId) {
        String normalizedScanId = requireText(scanId, "scanId");
        stopSpiderScanJob(normalizedScanId);
        trackDirectScanStopped(normalizedScanId);
        return "Direct spider scan stopped.\n"
                + "Scan ID: " + normalizedScanId + '\n'
                + "For retryable or HA-safe execution, prefer 'zap_queue_spider_scan'.";
    }

    /**
     * Start a standard spider scan and return the ZAP scan ID.
     */
    public String startSpiderScanJob(String targetUrl) {
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

    /**
     * Start an authenticated spider scan and return the ZAP scan ID.
     */
    public String startSpiderScanAsUserJob(String contextId,
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

    /**
     * Read current spider progress from ZAP.
     */
    public int getSpiderScanProgressPercent(String scanId) {
        try {
            ApiResponse resp = zap.spider.status(scanId);
            return Integer.parseInt(((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue());
        } catch (ClientApiException e) {
            log.error("Error retrieving spider status for ID {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving spider status for ID " + scanId, e);
        }
    }

    /**
     * Stop a running spider scan.
     */
    public void stopSpiderScanJob(String scanId) {
        try {
            zap.spider.stop(scanId);
        } catch (ClientApiException e) {
            log.error("Error stopping spider scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping spider scan " + scanId, e);
        }
    }

    /**
     * Require a non-blank string and return its trimmed value.
     */
    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    /**
     * Check whether a string contains non-whitespace text.
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String formatDirectStartMessage(String scanId, String targetUrl) {
        return String.format(
                "Direct spider scan started.%n" +
                        "Scan ID: %s%n" +
                        "Target URL: %s%n" +
                        "Use 'zap_spider_status' to monitor progress and 'zap_passive_scan_wait' before reading findings.%n" +
                        "For durable retries, queue visibility, or HA-safe execution, prefer 'zap_queue_spider_scan'.",
                scanId,
                targetUrl
        );
    }

    private String formatDirectAuthenticatedStartMessage(String scanId,
                                                         String contextId,
                                                         String userId,
                                                         String targetUrl,
                                                         String maxChildren,
                                                         String recurse,
                                                         String subtreeOnly) {
        return String.format(
                "Direct authenticated spider scan started.%n" +
                        "Scan ID: %s%n" +
                        "Context ID: %s%n" +
                        "User ID: %s%n" +
                        "Target URL: %s%n" +
                        "Max Children: %s%n" +
                        "Recurse: %s%n" +
                        "Subtree Only: %s%n" +
                        "Use 'zap_spider_status' to monitor progress and 'zap_passive_scan_wait' before reading findings.%n" +
                        "For durable retries, queue visibility, or HA-safe execution, prefer 'zap_queue_spider_scan_as_user'.",
                scanId,
                contextId,
                userId,
                targetUrl,
                maxChildren,
                recurse,
                subtreeOnly
        );
    }

    private void trackDirectScanStarted(String scanId, String workspaceId) {
        if (operationRegistry == null || !hasText(scanId)) {
            return;
        }
        operationRegistry.registerDirectScan("spider:" + scanId, workspaceId);
    }

    private void trackDirectScanStatus(String scanId, boolean completed) {
        if (operationRegistry == null || !hasText(scanId)) {
            return;
        }
        String operationId = "spider:" + scanId;
        if (completed) {
            operationRegistry.releaseDirectScan(operationId);
            return;
        }
        operationRegistry.touchDirectScan(operationId);
    }

    private void trackDirectScanStopped(String scanId) {
        if (operationRegistry == null || !hasText(scanId)) {
            return;
        }
        operationRegistry.releaseDirectScan("spider:" + scanId);
    }

    private String resolveWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        return clientWorkspaceResolver.resolveCurrentWorkspaceId();
    }
}
