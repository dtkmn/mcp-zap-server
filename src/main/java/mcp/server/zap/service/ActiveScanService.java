package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Service for managing ZAP Active Scans.
 * This service provides methods to start, check status, and stop active scans using the ZAP API.
 */
@Slf4j
@Service
public class ActiveScanService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;

    public ActiveScanService(ClientApi zap, 
                            UrlValidationService urlValidationService,
                            ScanLimitProperties scanLimitProperties) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

    /**
     * Start an active scan against the given URL and return the scanId.
     *
     * @param targetUrl Target URL to scan
     * @param recurse   Recurse into sub-paths? (true/false)
     * @param policy    Scan policy name (e.g. Default Policy, API Policy)
     * @return Scan ID of the started active scan
     */
    @Tool(
            name        = "zap_active_scan",
            description = "Start an active scan against the given URL and return the scanId"
    )
    public String activeScan(
            @ToolParam(description = "Target URL to scan (e.g., http://example.com)") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (true/false)") String recurse,
            @ToolParam(description = "Scan policy name (e.g. Default Policy, API Policy)") String policy
    ) {
        // Validate URL before scanning
        urlValidationService.validateUrl(targetUrl);

        try {
            // Configure active scanner
            zap.ascan.enableAllScanners(null);  // Enable all scanners

            // Configure timeouts and scan settings from properties
            zap.ascan.setOptionMaxScanDurationInMins(scanLimitProperties.getMaxActiveScanDurationInMins());
            zap.ascan.setOptionHostPerScan(scanLimitProperties.getHostPerScan());
            zap.ascan.setOptionThreadPerHost(scanLimitProperties.getThreadPerHost());

            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                    targetUrl,
                    recurse,
                    "false",
                    policy,   // policy name
                    null,   // method
                    null    // postData
            );

            if (scanResp == null) {
                throw new IllegalStateException("Failed to start scan on " + targetUrl + ": received null response");
            }

            String scanId = scanResp.getValue();
            log.info("Started active scan with ID {} on {} with policy: {}, maxDuration: {} mins", 
                    scanId, targetUrl, policy, scanLimitProperties.getMaxActiveScanDurationInMins());

            return "Active scan started with ID: " + scanId;
        } catch (ClientApiException e) {
            log.error("Error starting active scan on {}: {}", targetUrl, e.getMessage(), e);
            throw new ZapApiException("Error starting active scan on " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Get the current progress (0–100%) of a ZAP Active Scan job.
     *
     * @param scanId The scan ID returned when you started the Active Scan
     * @return Progress percentage as a string
     */
    @Tool(
            name        = "zap_active_scan_status",
            description = "Get the current progress (0–100%) of a ZAP Active Scan job"
    )
    public String getActiveScanStatus(
            @ToolParam(description = "The scan ID returned when you started the Active Scan") String scanId
    ) {
        try {
            ApiResponse resp = zap.ascan.status(scanId);

            if (!(resp instanceof ApiResponseElement)) {
                throw new IllegalStateException("Unexpected response from ascan.status(): " + resp);
            }
            String pct = ((ApiResponseElement) resp).getValue();
            return "Active Scan [" + scanId + "] is " + pct + "% complete";
        } catch (ClientApiException e) {
            log.error("Error starting active scan on {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving status for active scan " + scanId, e);
        }
    }

    /**
     * Stop a running Active Scan by its scanId.
     *
     * @param scanId The scan ID returned by zap_active_scan
     * @return Confirmation message
     */
    @Tool(
            name        = "zap_stop_active_scan",
            description = "Stop a running Active Scan by its scanId"
    )
    public String stopActiveScan(
            @ToolParam(description = "The scanId returned by zap_active_scan") String scanId
    ) {
        try {
            zap.ascan.stop(scanId);
            return "🛑 Stopped active scan with ID: " + scanId;
        } catch (ClientApiException e) {
            log.error("Error stopping active scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping active scan " + scanId, e);
        }
    }


    /**
     * Stop all running Active Scans in this ZAP session.
     *
     * @return Confirmation message
     */
    @Tool(
            name        = "zap_stop_all_scans",
            description = "Stop all running Active Scans in this ZAP session"
    )
    public String stopAllScans() {
        try {
            zap.ascan.stopAllScans();
            return "🛑 All active scans have been stopped.";
        } catch (ClientApiException e) {
            log.error("Error stopping active scans in this ZAP session: {}", e.getMessage(), e);
            throw new ZapApiException("Error stopping all active scans in this ZAP session", e);
        }
    }

}
