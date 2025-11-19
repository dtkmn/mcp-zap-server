package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;

    public AjaxSpiderService(ClientApi zap,
                            UrlValidationService urlValidationService) {
        this.zap = zap;
        this.urlValidationService = urlValidationService;
    }

    /**
     * Start an AJAX Spider scan against the given URL using a real browser.
     * This is more effective against JavaScript-heavy sites and WAF protection.
     *
     * @param targetUrl Target URL to scan with AJAX Spider
     * @return Status message with scan information
     */
    @Tool(name = "zap_ajax_spider", 
          description = "Start an AJAX Spider scan using a real browser (bypasses WAF and crawls JavaScript apps). " +
                       "Use this for sites that block regular spider or need JavaScript to render content.")
    public String startAjaxSpider(@ToolParam(description = "Target URL to AJAX spider scan (e.g., http://example.com)") String targetUrl) {
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
            
            return String.format(
                "AJAX Spider scan started successfully for URL: %s%n" +
                "Using real browser (Firefox headless) to crawl JavaScript content.%n" +
                "This method bypasses WAF protection and bot detection.%n" +
                "Using default scan duration and browser settings.%n" +
                "Use 'zap_ajax_spider_status' to check progress.",
                targetUrl
            );
            
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
    @Tool(name = "zap_ajax_spider_status", 
          description = "Get the current status of the AJAX Spider scan")
    public String getAjaxSpiderStatus() {
        try {
            ApiResponse statusResponse = zap.ajaxSpider.status();
            String status = ((ApiResponseElement) statusResponse).getValue();
            
            ApiResponse messagesResponse = zap.ajaxSpider.numberOfResults();
            String messagesCount = ((ApiResponseElement) messagesResponse).getValue();
            
            boolean isRunning = "running".equalsIgnoreCase(status);
            
            return String.format(
                "AJAX Spider Status: %s%n" +
                "Pages/URLs discovered: %s%n" +
                "%s",
                status,
                messagesCount,
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
    @Tool(name = "zap_ajax_spider_stop", 
          description = "Stop the currently running AJAX Spider scan")
    public String stopAjaxSpider() {
        try {
            zap.ajaxSpider.stop();
            log.info("AJAX Spider scan stopped");
            return "AJAX Spider scan stopped successfully";
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
    @Tool(name = "zap_ajax_spider_results", 
          description = "Get full results from the AJAX Spider scan including all discovered URLs")
    public String getAjaxSpiderResults() {
        try {
            ApiResponse response = zap.ajaxSpider.fullResults();
            return "AJAX Spider Results:\n" + response.toString(0);
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider results: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider results", e);
        }
    }
}
