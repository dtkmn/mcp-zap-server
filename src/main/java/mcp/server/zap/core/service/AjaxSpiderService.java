package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Service for managing ZAP AJAX Spider Scans.
 * 
 * The AJAX Spider uses a real browser (Firefox/Chrome via Selenium) to crawl JavaScript-heavy
 * applications and websites that block traditional HTTP-based spiders. This is particularly
 * useful for:
 * - Single Page Applications (SPAs) built with React, Angular, Vue
 * - Sites with WAF protection that block automated tools
 * - Sites that require JavaScript to render content
 * - Sites with bot detection mechanisms
 */
@Slf4j
@Service
public class AjaxSpiderService {
    public static final String QUEUE_SCAN_ID_PREFIX = "ajax-spider:";

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;

    /**
     * Build-time dependency injection constructor.
     */
    public AjaxSpiderService(ClientApi zap,
                            UrlValidationService urlValidationService) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    /**
     * Start an AJAX Spider scan against the given URL using a real browser.
     * This is more effective against JavaScript-heavy sites and WAF protection.
     *
     * @param targetUrl Target URL to scan with AJAX Spider
     * @return Status message with scan information
     */
    public String startAjaxSpider(String targetUrl) {
        String scanId = startAjaxSpiderJob(targetUrl);
        trackAjaxSpiderStarted(scanId, resolveWorkspaceId());
        log.info("AJAX Spider direct scan token {}", scanId);
        return formatDirectStartMessage(targetUrl);
    }

    /**
     * Queue/worker-facing start method that launches AJAX Spider and returns a synthetic durable scan id.
     */
    public String startAjaxSpiderJob(String targetUrl) {
        // Validate URL before scanning
        urlValidationService.validateUrl(targetUrl);

        try {
            // Check if AJAX Spider addon is available
            try {
                ApiResponse optionMaxDurationResponse = zap.ajaxSpider.optionMaxDuration();
                log.debug("AJAX Spider addon is available. Current max duration: {}", 
                         ((ApiResponseElement) optionMaxDurationResponse).getValue());
            } catch (ClientApiException e) {
                log.error("AJAX Spider addon is not available in this ZAP installation: {}", e.getMessage());
                throw new ZapApiException(
                    "AJAX Spider addon is not available. Please ensure ZAP is started with the AJAX Spider addon enabled. " +
                    "For Docker, use: zaproxy/zap-stable with -addoninstall ajaxSpider", e);
            }

            // Add URL to ZAP's scope (required for AJAX Spider to recognize the URL)
            try {
                // Access the URL first to add it to the Sites tree and ensure it's crawlable
                log.debug("Accessing URL to add to sites tree: {}", targetUrl);
                zap.core.accessUrl(targetUrl, "true");
                
                // Give ZAP a moment to process the URL
                Thread.sleep(1000);
                
                log.info("URL added to ZAP sites tree: {}", targetUrl);
            } catch (ClientApiException e) {
                log.warn("Could not access URL (will still attempt scan): {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Thread interrupted while waiting for URL to be added");
            }

            // Start the AJAX Spider scan with inScopeOnly=false to avoid scope issues
            // Using ZAP's default configuration: firefox-headless browser, default duration and browser count
            zap.ajaxSpider.scan(targetUrl, "false", "", "");
            
            log.info("AJAX Spider scan started for URL: {}", targetUrl);
            return QUEUE_SCAN_ID_PREFIX + System.currentTimeMillis();
            
        } catch (ClientApiException e) {
            log.error("Error launching AJAX Spider for URL {}: {}", targetUrl, e.getMessage(), e);
            throw new ZapApiException("Error launching AJAX Spider for URL " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    /**
     * Get the status of the AJAX Spider scan.
     *
     * @return Current status of AJAX Spider scan
     */
    public String getAjaxSpiderStatus() {
        try {
            AjaxSpiderStatus ajaxStatus = readAjaxSpiderStatus();
            trackAjaxSpiderStatus(ajaxStatus.running());
            
            boolean isRunning = ajaxStatus.running();
            
            return String.format(
                "AJAX Spider Status: %s%n" +
                "Pages/URLs discovered: %s%n" +
                "%s",
                ajaxStatus.status(),
                ajaxStatus.discoveredCount(),
                isRunning ? "Scan is in progress..." : "Scan completed."
            );
            
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider status: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider status", e);
        }
    }

    /**
     * Stop the currently running AJAX Spider scan.
     *
     * @return Confirmation message
     */
    public String stopAjaxSpider() {
        stopAjaxSpiderJob(null);
        trackAjaxSpiderStopped();
        return "AJAX Spider scan stopped successfully";
    }

    public void stopAjaxSpiderJob(String ignoredScanId) {
        try {
            zap.ajaxSpider.stop();
            log.info("AJAX Spider scan stopped");
        } catch (ClientApiException e) {
            log.error("Error stopping AJAX Spider: {}", e.getMessage(), e);
            throw new ZapApiException("Error stopping AJAX Spider", e);
        }
    }

    /**
     * Get the full results from the AJAX Spider scan.
     *
     * @return Full results including all discovered URLs
     */
    public String getAjaxSpiderResults() {
        try {
            ApiResponse response = zap.ajaxSpider.fullResults();
            return "AJAX Spider Results:\n" + response.toString(0);
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider results: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider results", e);
        }
    }

    public int getAjaxSpiderProgressPercent(String ignoredScanId) {
        try {
            return readAjaxSpiderStatus().running() ? 0 : 100;
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider progress: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider progress", e);
        }
    }

    private AjaxSpiderStatus readAjaxSpiderStatus() throws ClientApiException {
        ApiResponse statusResponse = zap.ajaxSpider.status();
        String status = ((ApiResponseElement) statusResponse).getValue();

        ApiResponse messagesResponse = zap.ajaxSpider.numberOfResults();
        String messagesCount = ((ApiResponseElement) messagesResponse).getValue();
        boolean running = "running".equalsIgnoreCase(status);
        return new AjaxSpiderStatus(status, messagesCount, running);
    }

    private String formatDirectStartMessage(String targetUrl) {
        return String.format(
                "AJAX Spider scan started successfully for URL: %s%n" +
                        "Using real browser (Firefox headless) to crawl JavaScript content.%n" +
                        "This method bypasses WAF protection and bot detection.%n" +
                        "Using default scan duration and browser settings.%n" +
                        "Use 'zap_ajax_spider_status' to check progress.",
                targetUrl
        );
    }

    private void trackAjaxSpiderStarted(String scanId, String workspaceId) {
        if (operationRegistry == null || scanId == null || scanId.isBlank()) {
            return;
        }
        operationRegistry.registerDirectScan("ajax:" + scanId, workspaceId);
    }

    private void trackAjaxSpiderStatus(boolean running) {
        if (operationRegistry == null) {
            return;
        }
        if (running) {
            operationRegistry.touchDirectScansByPrefix("ajax:");
            return;
        }
        operationRegistry.releaseDirectScansByPrefix("ajax:");
    }

    private void trackAjaxSpiderStopped() {
        if (operationRegistry == null) {
            return;
        }
        operationRegistry.releaseDirectScansByPrefix("ajax:");
    }

    private String resolveWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        return clientWorkspaceResolver.resolveCurrentWorkspaceId();
    }

    private record AjaxSpiderStatus(String status, String discoveredCount, boolean running) {
    }
}
