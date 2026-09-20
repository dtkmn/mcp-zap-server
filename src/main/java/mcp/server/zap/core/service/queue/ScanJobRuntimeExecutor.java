package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.ClientSpiderService;
import mcp.server.zap.core.service.SpiderScanService;

import java.util.Map;
import java.util.function.BiConsumer;

public class ScanJobRuntimeExecutor {
    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final AjaxSpiderService ajaxSpiderService;
    private final ClientSpiderService clientSpiderService;
    private final BiConsumer<ScanJobStartTarget, String> onAjaxStartAccepted;

    public ScanJobRuntimeExecutor(ActiveScanService activeScanService,
                                  SpiderScanService spiderScanService,
                                  AjaxSpiderService ajaxSpiderService) {
        this(activeScanService, spiderScanService, ajaxSpiderService, null, null);
    }

    public ScanJobRuntimeExecutor(ActiveScanService activeScanService,
                                  SpiderScanService spiderScanService,
                                  AjaxSpiderService ajaxSpiderService,
                                  ClientSpiderService clientSpiderService,
                                  BiConsumer<ScanJobStartTarget, String> onAjaxStartAccepted) {
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
        this.clientSpiderService = clientSpiderService;
        this.onAjaxStartAccepted = onAjaxStartAccepted;
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
            case CLIENT_SPIDER -> requireClientSpiderService().startClientSpiderJob(
                    parameters.get(ScanJobParameterNames.TARGET_URL),
                    parameters.containsKey(ScanJobParameterNames.MAX_DEPTH)
                            ? Integer.valueOf(parameters.get(ScanJobParameterNames.MAX_DEPTH)) : null
            );
        };
    }

    public String startScan(ScanJobStartTarget target) {
        if (target.type() == ScanJobType.AJAX_SPIDER) {
            if (onAjaxStartAccepted != null) {
                return requireAjaxSpiderService().startAjaxSpiderJob(
                        target.parameters().get(ScanJobParameterNames.TARGET_URL), target.jobId(), target.claimToken(),
                        scanId -> onAjaxStartAccepted.accept(target, scanId));
            }
            return requireAjaxSpiderService().startAjaxSpiderJob(
                    target.parameters().get(ScanJobParameterNames.TARGET_URL), target.jobId(), target.claimToken());
        }
        return startScan(target.type(), target.parameters());
    }

    public int readProgress(ScanJobType type, String scanId) {
        return switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.getActiveScanProgressPercent(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.getSpiderScanProgressPercent(scanId);
            case CLIENT_SPIDER -> requireClientSpiderService().getClientSpiderProgressPercent(scanId);
            // AJAX has no percentage: these values only signal the queue lifecycle.
            // A stopped crawler does not confirm a successful crawl.
            case AJAX_SPIDER -> requireAjaxSpiderService().isAjaxSpiderRunning() ? 0 : 100;
        };
    }

    public void stopScan(ScanJobType type, String scanId) {
        switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.stopActiveScanJob(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.stopSpiderScanJob(scanId);
            case AJAX_SPIDER -> requireAjaxSpiderService().stopAjaxSpiderJob();
            case CLIENT_SPIDER -> requireClientSpiderService().stopClientSpiderJob(scanId);
        }
    }

    private AjaxSpiderService requireAjaxSpiderService() {
        if (ajaxSpiderService == null) {
            throw new IllegalStateException("AJAX Spider service is not available in this runtime");
        }
        return ajaxSpiderService;
    }

    private ClientSpiderService requireClientSpiderService() {
        if (clientSpiderService == null) {
            throw new IllegalStateException("Client Spider service is not available in this runtime");
        }
        return clientSpiderService;
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
