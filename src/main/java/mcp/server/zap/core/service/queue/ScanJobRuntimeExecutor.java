package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.SpiderScanService;

import java.util.Map;

public class ScanJobRuntimeExecutor {
    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final AjaxSpiderService ajaxSpiderService;

    public ScanJobRuntimeExecutor(ActiveScanService activeScanService,
                                  SpiderScanService spiderScanService,
                                  AjaxSpiderService ajaxSpiderService) {
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
    }

    public String startScan(ScanJobType type, Map<String, String> parameters) {
        return switch (type) {
            case ACTIVE_SCAN -> activeScanService.startActiveScanJob(
                    parameters.get(ScanJobParameterNames.TARGET_URL),
                    parameters.get(ScanJobParameterNames.RECURSE),
                    normalizeBlankToNull(parameters.get(ScanJobParameterNames.POLICY))
            );
            case ACTIVE_SCAN_AS_USER -> activeScanService.startActiveScanAsUserJob(
                    parameters.get(ScanJobParameterNames.CONTEXT_ID),
                    parameters.get(ScanJobParameterNames.USER_ID),
                    parameters.get(ScanJobParameterNames.TARGET_URL),
                    parameters.get(ScanJobParameterNames.RECURSE),
                    normalizeBlankToNull(parameters.get(ScanJobParameterNames.POLICY))
            );
            case SPIDER_SCAN -> spiderScanService.startSpiderScanJob(
                    parameters.get(ScanJobParameterNames.TARGET_URL)
            );
            case SPIDER_SCAN_AS_USER -> spiderScanService.startSpiderScanAsUserJob(
                    parameters.get(ScanJobParameterNames.CONTEXT_ID),
                    parameters.get(ScanJobParameterNames.USER_ID),
                    parameters.get(ScanJobParameterNames.TARGET_URL),
                    normalizeBlankToNull(parameters.get(ScanJobParameterNames.MAX_CHILDREN)),
                    parameters.get(ScanJobParameterNames.RECURSE),
                    parameters.get(ScanJobParameterNames.SUBTREE_ONLY)
            );
            case AJAX_SPIDER -> requireAjaxSpiderService().startAjaxSpiderJob(
                    parameters.get(ScanJobParameterNames.TARGET_URL)
            );
        };
    }

    public int readProgress(ScanJobType type, String scanId) {
        return switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.getActiveScanProgressPercent(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.getSpiderScanProgressPercent(scanId);
            case AJAX_SPIDER -> requireAjaxSpiderService().getAjaxSpiderProgressPercent();
        };
    }

    public void stopScan(ScanJobType type, String scanId) {
        switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.stopActiveScanJob(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.stopSpiderScanJob(scanId);
            case AJAX_SPIDER -> requireAjaxSpiderService().stopAjaxSpiderJob();
        }
    }

    private AjaxSpiderService requireAjaxSpiderService() {
        if (ajaxSpiderService == null) {
            throw new IllegalStateException("AJAX Spider service is not available in this runtime");
        }
        return ajaxSpiderService;
    }

    private String normalizeBlankToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
