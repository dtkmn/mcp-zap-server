package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing access contract for engine inventory and raw alert summaries.
 */
public interface EngineInventoryAccess {

    List<InventoryAlertSummary> loadAlertSummaries(String baseUrl);

    List<String> listHosts();

    List<String> listSites();

    List<String> listUrls(String baseUrl);

    record InventoryAlertSummary(String name, String risk, String url) {
    }
}
