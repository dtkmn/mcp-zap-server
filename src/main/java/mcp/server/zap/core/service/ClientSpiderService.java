package mcp.server.zap.core.service;

import java.util.Map;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ClientSpiderScanRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Direct and queued browser crawls with ZAP's scan-specific Client Spider API. */
@Service
public class ClientSpiderService {
    private static final String OPERATION_ID_PREFIX = "client-spider:";

    private final EngineScanExecution engineScanExecution;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ScanHistoryLedgerService scanHistoryLedgerService;

    public ClientSpiderService(EngineScanExecution engineScanExecution,
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

    public String startClientSpider(String targetUrl, Integer maxDepth) {
        return startClientSpider(targetUrl, maxDepth, null, null);
    }

    public String startClientSpider(String targetUrl, Integer maxDepth, String contextName, String userName) {
        String scanId = startClientSpiderJob(targetUrl, maxDepth, contextName, userName);
        if (operationRegistry != null) {
            String workspaceId = clientWorkspaceResolver == null ? "default-workspace"
                    : clientWorkspaceResolver.resolveCurrentWorkspaceId();
            operationRegistry.registerDirectScan(OPERATION_ID_PREFIX + scanId, workspaceId);
        }
        if (scanHistoryLedgerService != null) {
            scanHistoryLedgerService.recordDirectScanStarted("client_spider", scanId, targetUrl,
                    contextName != null && !contextName.isBlank() ? Map.of("authenticated", "true") : Map.of());
        }
        return String.format(
                "Direct Client Spider scan started.%n"
                        + "Scan ID: %s%n"
                        + "Target URL: %s%n"
                        + "Use 'zap_client_spider_status' to monitor progress and 'zap_passive_scan_wait' before reading findings.%n"
                        + "For durable retries, queue visibility, or HA-safe execution, prefer 'zap_queue_client_spider_scan'.",
                scanId, targetUrl);
    }

    public String startClientSpiderJob(String targetUrl, Integer maxDepth) {
        return startClientSpiderJob(targetUrl, maxDepth, null, null);
    }

    public String startClientSpiderJob(String targetUrl, Integer maxDepth, String contextName, String userName) {
        String normalizedContextName = normalizeOptionalName(contextName);
        String normalizedUserName = normalizeOptionalName(userName);
        if ((normalizedContextName == null) != (normalizedUserName == null)) {
            throw new IllegalArgumentException("contextName and userName must both be provided for an authenticated Client Spider crawl");
        }
        urlValidationService.validateUrl(targetUrl);
        int effectiveMaxDepth = maxDepth == null ? scanLimitProperties.getSpiderMaxDepth() : maxDepth;
        if (effectiveMaxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be zero (unlimited) or greater");
        }
        int maxDurationMinutes = scanLimitProperties.getMaxSpiderScanDurationInMins();
        if (maxDurationMinutes < 0) {
            throw new IllegalArgumentException("maxSpiderScanDurationInMins must be zero (unlimited) or greater");
        }
        return engineScanExecution.startClientSpiderScan(
                new ClientSpiderScanRequest(targetUrl, effectiveMaxDepth, maxDurationMinutes,
                        normalizedContextName, normalizedUserName));
    }

    public String getClientSpiderStatus(String scanId) {
        String normalizedScanId = requireScanId(scanId);
        int progress = getClientSpiderProgressPercent(normalizedScanId);
        boolean finished = progress >= 100;
        if (operationRegistry != null) {
            if (finished) {
                operationRegistry.releaseDirectScan(OPERATION_ID_PREFIX + normalizedScanId);
            } else {
                operationRegistry.touchDirectScan(OPERATION_ID_PREFIX + normalizedScanId);
            }
        }
        return String.format(
                "Direct Client Spider scan status:%n"
                        + "Scan ID: %s%n"
                        + "Progress: %d%%%n"
                        + "%s",
                normalizedScanId, progress,
                finished
                        ? "Scan is no longer running. ZAP reports 100% for stopped scans as well. "
                                + "Run 'zap_passive_scan_wait' before reading findings or generating reports."
                        : "Use 'zap_client_spider_stop' to stop this direct crawl.");
    }

    public int getClientSpiderProgressPercent(String scanId) {
        return engineScanExecution.readClientSpiderProgressPercent(requireScanId(scanId));
    }

    public String stopClientSpider(String scanId) {
        String normalizedScanId = requireScanId(scanId);
        stopClientSpiderJob(normalizedScanId);
        if (operationRegistry != null) {
            operationRegistry.releaseDirectScan(OPERATION_ID_PREFIX + normalizedScanId);
        }
        return "Direct Client Spider stop requested.\nScan ID: " + normalizedScanId;
    }

    public void stopClientSpiderJob(String scanId) {
        engineScanExecution.stopClientSpiderScan(requireScanId(scanId));
    }

    private String normalizeOptionalName(String name) {
        return name == null || name.isBlank() ? null : name.trim();
    }

    private String requireScanId(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            throw new IllegalArgumentException("scanId cannot be null or blank");
        }
        return scanId.trim();
    }
}
