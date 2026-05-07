package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineInventoryAccess;
import mcp.server.zap.core.gateway.EngineInventoryAccess.InventoryAlertSummary;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * CoreService provides inventory methods for retrieving alerts, hosts, sites, and URLs.
 */
@Service
public class CoreService {

    private final EngineInventoryAccess inventoryAccess;
    private ScanHistoryLedgerService scanHistoryLedgerService;

    /**
     * Build-time dependency injection constructor.
     */
    public CoreService(EngineInventoryAccess inventoryAccess) {
        this.inventoryAccess = inventoryAccess;
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    /**
     * Retrieve alerts for a given base URL.
     *
     * @param baseUrl The base URL to filter alerts (optional).
     * @return A list of alert summaries.
     */
    public List<String> getAlerts(String baseUrl) {
        String normalizedBaseUrl = requireAuthorizedBaseUrl(baseUrl, "alerts");
        UrlScope scope = UrlScope.parse(normalizedBaseUrl);
        List<String> alerts = new ArrayList<>();
        for (InventoryAlertSummary alert : inventoryAccess.loadAlertSummaries(normalizedBaseUrl)) {
            if (!scope.contains(alert.url())) {
                continue;
            }
            alerts.add(String.format("%s (risk: %s) at %s", alert.name(), alert.risk(), alert.url()));
        }
        return List.copyOf(alerts);
    }

    /**
     * Retrieve the list of hosts accessed through/by ZAP.
     *
     * @return A list of host names.
     */
    public List<String> getHosts() {
        ensureLedgerAvailable("hosts");
        return scanHistoryLedgerService.visibleScanTargetHosts();
    }

    /**
     * Retrieve the list of sites accessed through/by ZAP.
     *
     * @return A list of site URLs.
     */
    public List<String> getSites() {
        ensureLedgerAvailable("sites");
        return scanHistoryLedgerService.visibleScanTargetBaseUrls();
    }

    /**
     * Retrieve the list of URLs accessed through/by ZAP, optionally filtered by base URL.
     *
     * @param baseUrl The base URL to filter URLs (optional).
     * @return A list of URLs.
     */
    public List<String> getUrls(String baseUrl) {
        String normalizedBaseUrl = requireAuthorizedBaseUrl(baseUrl, "URLs");
        UrlScope scope = UrlScope.parse(normalizedBaseUrl);
        return inventoryAccess.listUrls(normalizedBaseUrl).stream()
                .filter(scope::contains)
                .toList();
    }

    private String requireAuthorizedBaseUrl(String baseUrl, String operationLabel) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required for " + operationLabel + " inventory reads.");
        }
        ensureLedgerAvailable(operationLabel);
        String normalizedBaseUrl = baseUrl.trim();
        if (!scanHistoryLedgerService.hasVisibleScanEvidenceForTarget(normalizedBaseUrl)) {
            throw new IllegalArgumentException("No visible scan evidence exists for baseUrl: " + normalizedBaseUrl);
        }
        return normalizedBaseUrl;
    }

    private void ensureLedgerAvailable(String operationLabel) {
        if (scanHistoryLedgerService == null) {
            throw new IllegalStateException("Scan history ledger is required for scoped " + operationLabel + " inventory reads.");
        }
    }

}
