package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.NativeScanLaunch;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ClientSpiderScanRequest;
import org.springframework.stereotype.Service;

/** Queued Client Spider execution and legacy native status and stop operations. */
@Service
public class ClientSpiderService {
    private final EngineScanExecution engineScanExecution;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;

    public ClientSpiderService(EngineScanExecution engineScanExecution,
                               UrlValidationService urlValidationService,
                               ScanLimitProperties scanLimitProperties) {
        this.engineScanExecution = engineScanExecution;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

public String startClientSpiderJob(String targetUrl, Integer maxDepth, String contextName, String userName, NativeScanLaunch launch) {
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
        ClientSpiderScanRequest request = new ClientSpiderScanRequest(targetUrl, effectiveMaxDepth, maxDurationMinutes,
                        normalizedContextName, normalizedUserName);
        return launch == NativeScanLaunch.UNTRACKED
                ? engineScanExecution.startClientSpiderScan(request)
                : engineScanExecution.startClientSpiderScan(request, launch);
    }

    public String getClientSpiderStatus(String scanId) {
        String normalizedScanId = requireScanId(scanId);
        int progress = getClientSpiderProgressPercent(normalizedScanId);
        boolean finished = progress >= 100;
        return String.format(
                "Direct Client Spider scan status:%n"
                        + "Scan ID: %s%n"
                        + "Progress: %d%%%n"
                        + "%s",
                normalizedScanId, progress,
                finished
                        ? "Scan is no longer running. ZAP reports 100% for stopped scans as well. "
                                + "Run 'zap_passive_scan_wait' before reading findings or generating reports."
                        : "");
    }

    public int getClientSpiderProgressPercent(String scanId) {
        return engineScanExecution.readClientSpiderProgressPercent(requireScanId(scanId));
    }

    public String stopClientSpider(String scanId) {
        String normalizedScanId = requireScanId(scanId);
        stopClientSpiderJob(normalizedScanId);
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
