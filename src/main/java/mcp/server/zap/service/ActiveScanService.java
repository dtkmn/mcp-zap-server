package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;

/**
 * Service for managing ZAP Active Scans.
 * This service provides methods to start, check status, and stop active scans using the ZAP API.
 */
@Slf4j
@Service
public class ActiveScanService {

    private final ClientApi zap;

    public ActiveScanService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * Start an active scan against the given URL and return the scanId.
     *
     * @param targetUrl Target URL to scan
     * @param recurse   Recurse into sub-paths? (true/false)
     * @param policy    Scan policy name (e.g. Default Policy, API Policy)
     * @return Scan ID of the started active scan
     * @throws Exception If an error occurs while starting the scan
     */
    @Tool(
            name        = "zap_active_scan",
            description = "Start an active scan against the given URL and return the scanId"
    )
    public String activeScan(
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (true/false)") String recurse,
            @ToolParam(description = "Scan policy name (e.g. Default Policy, API Policy)") String policy
    ) throws Exception {
        // Configure active scanner
        zap.ascan.enableAllScanners(null);  // Enable all scanners

        // Configure global timeouts and scan settings
        zap.ascan.setOptionMaxScanDurationInMins(0);    // No duration limit
//        zap.ascan.setOptionTimeoutInSecs(60);           // 60 seconds per rule
        zap.ascan.setOptionHostPerScan(0);              // No limit on hosts
        zap.ascan.setOptionThreadPerHost(10);           // Parallel scanning
        zap.ascan.setOptionDelayInMs(500);               // No delay between requests
//        zap.selenium.setOptionBrowserWithoutProxyTimeout(60);  // Browser timeout

        ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                targetUrl,
                recurse,
                "false",
                policy,   // policy name
                null,   // method
                null    // postData
        );

        if (scanResp == null) {
            throw new IllegalStateException("Failed to start scan on " + targetUrl + ": " + scanResp);
        }

        String scanId = scanResp.getValue();
        log.info("Started active scan with ID {} on {}", scanId, targetUrl);

        return "Active scan started with ID: " + scanId;
    }

    /**
     * Get the current progress (0–100%) of a ZAP Active Scan job.
     *
     * @param scanId The scan ID returned when you started the Active Scan
     * @return Progress percentage as a string
     * @throws Exception If an error occurs while retrieving the status
     */
    @Tool(
            name        = "zap_active_scan_status",
            description = "Get the current progress (0–100%) of a ZAP Active Scan job"
    )
    public String getActiveScanStatus(
            @ToolParam(description = "The scan ID returned when you started the Active Scan") String scanId
    ) throws Exception {
        // 1) Call the typed status wrapper
        ApiResponse resp = zap.ascan.status(scanId);

        // 2) Validate & extract
        if (!(resp instanceof ApiResponseElement)) {
            throw new IllegalStateException("Unexpected response from ascan.status(): " + resp);
        }
        String pct = ((ApiResponseElement) resp).getValue();

        // 3) Return a human-friendly message
        return "Active Scan [" + scanId + "] is " + pct + "% complete";
    }

    /**
     * Stop a running Active Scan by its scanId.
     *
     * @param scanId The scan ID returned by zap_active_scan
     * @return Confirmation message
     * @throws Exception If an error occurs while stopping the scan
     */
    @Tool(
            name        = "zap_stop_active_scan",
            description = "Stop a running Active Scan by its scanId"
    )
    public String stopActiveScan(
            @ToolParam(description = "The scanId returned by zap_active_scan") String scanId
    ) throws Exception {
        // This will abort the specified scan
        zap.ascan.stop(scanId);
        return "🛑 Stopped active scan with ID: " + scanId;
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
        } catch (Exception e) {
            log.error("Error stopping all active scans: {}", e.getMessage(), e);
            return "❌ Error stopping all active scans: " + e.getMessage();
        }
    }

}
