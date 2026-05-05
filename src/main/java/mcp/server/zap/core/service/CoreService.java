package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineInventoryAccess;
import mcp.server.zap.core.gateway.EngineInventoryAccess.InventoryAlertSummary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * CoreService provides inventory methods for retrieving alerts, hosts, sites, and URLs.
 */
@Service
public class CoreService {

    private final EngineInventoryAccess inventoryAccess;

    /**
     * Build-time dependency injection constructor.
     */
    public CoreService(EngineInventoryAccess inventoryAccess) {
        this.inventoryAccess = inventoryAccess;
    }

    /**
     * Retrieve alerts for a given base URL.
     *
     * @param baseUrl The base URL to filter alerts (optional).
     * @return A list of alert summaries.
     */
    public List<String> getAlerts(String baseUrl) {
        List<String> alerts = new ArrayList<>();
        for (InventoryAlertSummary alert : inventoryAccess.loadAlertSummaries(baseUrl)) {
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
        return inventoryAccess.listHosts();
    }

    /**
     * Retrieve the list of sites accessed through/by ZAP.
     *
     * @return A list of site URLs.
     */
    public List<String> getSites() {
        return inventoryAccess.listSites();
    }

    /**
     * Retrieve the list of URLs accessed through/by ZAP, optionally filtered by base URL.
     *
     * @param baseUrl The base URL to filter URLs (optional).
     * @return A list of URLs.
     */
    public List<String> getUrls(String baseUrl) {
        return inventoryAccess.listUrls(baseUrl);
    }

}
