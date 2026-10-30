package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.NativeScanLaunch;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


/**
 * Service for direct and queue-managed spider scan operations.
 */
@Service
public class SpiderScanService {

    private final EngineScanExecution engineScanExecution;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private OperationRegistry operationRegistry;

    /**
     * Build-time dependency injection constructor.
     */
    public SpiderScanService(EngineScanExecution engineScanExecution,
                             UrlValidationService urlValidationService,
                             ScanLimitProperties scanLimitProperties) {
        this.engineScanExecution = engineScanExecution;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
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
public String startSpiderScanJob(String targetUrl, NativeScanLaunch launch) {
        urlValidationService.validateUrl(targetUrl);
        SpiderScanRequest request = new SpiderScanRequest(
                targetUrl,
                scanLimitProperties.getSpiderMaxDepth(),
                scanLimitProperties.getSpiderThreadCount(),
                scanLimitProperties.getMaxSpiderScanDurationInMins(),
                scanLimitProperties.getSpiderMaxChildren()
        );
        return launch == NativeScanLaunch.UNTRACKED
                ? engineScanExecution.startSpiderScan(request)
                : engineScanExecution.startSpiderScan(request, launch);
    }

    /**
     * Start an authenticated spider scan and return the ZAP scan ID.
     */
public String startSpiderScanAsUserJob(String contextId,
                                           String userId,
                                           String targetUrl,
                                           String maxChildren,
                                           String recurse,
                                           String subtreeOnly, NativeScanLaunch launch) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");
        urlValidationService.validateUrl(targetUrl);

        String effectiveMaxChildren = resolveMaxChildren(maxChildren);
        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectiveSubtreeOnly = hasText(subtreeOnly) ? subtreeOnly.trim() : "false";

        AuthenticatedSpiderScanRequest request = new AuthenticatedSpiderScanRequest(
                normalizedContextId,
                normalizedUserId,
                targetUrl,
                effectiveMaxChildren,
                effectiveRecurse,
                effectiveSubtreeOnly,
                scanLimitProperties.getSpiderThreadCount(),
                scanLimitProperties.getMaxSpiderScanDurationInMins(),
                scanLimitProperties.getSpiderMaxDepth()
        );
        return launch == NativeScanLaunch.UNTRACKED
                ? engineScanExecution.startSpiderScanAsUser(request)
                : engineScanExecution.startSpiderScanAsUser(request, launch);
    }

    private String resolveMaxChildren(String maxChildren) {
        int limit;
        try {
            limit = hasText(maxChildren)
                    ? Integer.parseInt(maxChildren.trim())
                    : scanLimitProperties.getSpiderMaxChildren();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("maxChildren must be zero (unlimited) or a positive integer", e);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("maxChildren must be zero (unlimited) or a positive integer");
        }
        return Integer.toString(limit);
    }

    /**
     * Read current spider progress from ZAP.
     */
    public int getSpiderScanProgressPercent(String scanId) {
        return engineScanExecution.readSpiderProgressPercent(scanId);
    }

    /**
     * Stop a running spider scan.
     */
    public void stopSpiderScanJob(String scanId) {
        engineScanExecution.stopSpiderScan(scanId);
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

}
