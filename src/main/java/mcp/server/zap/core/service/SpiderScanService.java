package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for direct and queue-managed spider scan operations.
 */
@Service
public class SpiderScanService {

    private final EngineScanExecution engineScanExecution;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ScanHistoryLedgerService scanHistoryLedgerService;

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

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    public String startSpiderScan(String targetUrl) {
        String scanId = startSpiderScanJob(targetUrl);
        trackDirectScanStarted(scanId, resolveWorkspaceId());
        recordDirectScanStarted("spider_scan", scanId, targetUrl, Map.of());
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
        recordDirectScanStarted("spider_scan_as_user", scanId, targetUrl, Map.of(
                "authenticated", "true",
                "maxChildren", effectiveMaxChildren,
                "recurse", effectiveRecurse,
                "subtreeOnly", effectiveSubtreeOnly
        ));
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
        urlValidationService.validateUrl(targetUrl);
        return engineScanExecution.startSpiderScan(new SpiderScanRequest(
                targetUrl,
                scanLimitProperties.getSpiderMaxDepth(),
                scanLimitProperties.getSpiderThreadCount(),
                scanLimitProperties.getMaxSpiderScanDurationInMins()
        ));
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

        return engineScanExecution.startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                normalizedContextId,
                normalizedUserId,
                targetUrl,
                effectiveMaxChildren,
                effectiveRecurse,
                effectiveSubtreeOnly,
                scanLimitProperties.getSpiderThreadCount(),
                scanLimitProperties.getMaxSpiderScanDurationInMins()
        ));
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

    private void recordDirectScanStarted(String operationKind,
                                         String scanId,
                                         String targetUrl,
                                         Map<String, String> metadata) {
        if (scanHistoryLedgerService == null) {
            return;
        }
        scanHistoryLedgerService.recordDirectScanStarted(operationKind, scanId, targetUrl, metadata);
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
