package mcp.server.zap.core.gateway;

/**
 * Gateway-facing execution contract for browser-backed crawling.
 */
public interface EngineAjaxSpiderExecution {

    String startAjaxSpider(AjaxSpiderScanRequest request);

    AjaxSpiderStatus readAjaxSpiderStatus();

    void stopAjaxSpider();

    String loadAjaxSpiderResults();

    record AjaxSpiderScanRequest(String targetUrl) {
    }

    record AjaxSpiderStatus(String status, String discoveredCount, boolean running) {
    }
}
