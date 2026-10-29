package mcp.server.zap.core.gateway;

/**
 * Gateway-facing execution contract for browser-backed crawling.
 */
public interface EngineAjaxSpiderExecution {

    String startAjaxSpider(AjaxSpiderScanRequest request);

    default String startAjaxSpider(AjaxSpiderScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startAjaxSpider(request);
        launch.accepted(id);
        return id;
    }

    AjaxSpiderStatus readAjaxSpiderStatus();

    void stopAjaxSpider();

    String loadAjaxSpiderResults();

    record AjaxSpiderScanRequest(String targetUrl) {
    }

    record AjaxSpiderStatus(String status, String discoveredCount, boolean running) {
    }
}
