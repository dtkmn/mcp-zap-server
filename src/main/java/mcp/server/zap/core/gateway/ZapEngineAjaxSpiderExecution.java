package mcp.server.zap.core.gateway;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the browser spider execution boundary.
 */
@Slf4j
@Component
public class ZapEngineAjaxSpiderExecution implements EngineAjaxSpiderExecution {
    private static final String ZAP_QUEUE_SCAN_ID_PREFIX = "ajax-spider:";

    private final ClientApi zap;

    public ZapEngineAjaxSpiderExecution(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public String startAjaxSpider(AjaxSpiderScanRequest request) {
        try {
            assertAjaxSpiderAvailable();
            accessTarget(request.targetUrl());
            zap.ajaxSpider.scan(request.targetUrl(), "false", "", "");
            log.info("AJAX Spider scan started for URL: {}", request.targetUrl());
            return ZAP_QUEUE_SCAN_ID_PREFIX + System.currentTimeMillis();
        } catch (ClientApiException e) {
            log.error("Error launching AJAX Spider for URL {}: {}", request.targetUrl(), e.getMessage(), e);
            throw new ZapApiException("Error launching AJAX Spider for URL " + request.targetUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public AjaxSpiderStatus readAjaxSpiderStatus() {
        try {
            ApiResponse statusResponse = zap.ajaxSpider.status();
            String status = responseValue(statusResponse, "ajaxSpider.status()");
            ApiResponse messagesResponse = zap.ajaxSpider.numberOfResults();
            String messagesCount = responseValue(messagesResponse, "ajaxSpider.numberOfResults()");
            boolean running = "running".equalsIgnoreCase(status);
            return new AjaxSpiderStatus(status, messagesCount, running);
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider status: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider status", e);
        }
    }

    @Override
    public void stopAjaxSpider() {
        try {
            zap.ajaxSpider.stop();
            log.info("AJAX Spider scan stopped");
        } catch (ClientApiException e) {
            log.error("Error stopping AJAX Spider: {}", e.getMessage(), e);
            throw new ZapApiException("Error stopping AJAX Spider", e);
        }
    }

    @Override
    public String loadAjaxSpiderResults() {
        try {
            ApiResponse response = zap.ajaxSpider.fullResults();
            return response.toString(0);
        } catch (ClientApiException e) {
            log.error("Error retrieving AJAX Spider results: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving AJAX Spider results", e);
        }
    }

    private void assertAjaxSpiderAvailable() {
        try {
            ApiResponse optionMaxDurationResponse = zap.ajaxSpider.optionMaxDuration();
            log.debug("AJAX Spider addon is available. Current max duration: {}",
                    responseValue(optionMaxDurationResponse, "ajaxSpider.optionMaxDuration()"));
        } catch (ClientApiException e) {
            log.error("AJAX Spider addon is not available in this ZAP installation: {}", e.getMessage());
            throw new ZapApiException(
                    "AJAX Spider addon is not available. Please ensure ZAP is started with the AJAX Spider addon enabled. "
                            + "For Docker, use: zaproxy/zap-stable with -addoninstall ajaxSpider", e);
        }
    }

    private void accessTarget(String targetUrl) {
        try {
            log.debug("Accessing URL to add to sites tree: {}", targetUrl);
            zap.core.accessUrl(targetUrl, "true");
            Thread.sleep(1000);
            log.info("URL added to ZAP sites tree: {}", targetUrl);
        } catch (ClientApiException e) {
            log.warn("Could not access URL (will still attempt scan): {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrupted while waiting for URL to be added");
        }
    }

    private String responseValue(ApiResponse response, String operationName) {
        if (!(response instanceof ApiResponseElement element)) {
            throw new IllegalStateException("Unexpected response from " + operationName + ": " + response);
        }
        return element.getValue();
    }
}
