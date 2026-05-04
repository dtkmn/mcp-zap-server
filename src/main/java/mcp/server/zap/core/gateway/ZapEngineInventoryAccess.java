package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the inventory access boundary.
 */
@Slf4j
@Component
public class ZapEngineInventoryAccess implements EngineInventoryAccess {

    private final ClientApi zap;

    public ZapEngineInventoryAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public List<InventoryAlertSummary> loadAlertSummaries(String baseUrl) {
        try {
            ApiResponseList response = (ApiResponseList) zap.core.alerts(baseUrl != null ? baseUrl : "", "0", "-1");
            List<InventoryAlertSummary> alerts = new ArrayList<>();
            for (ApiResponse item : response.getItems()) {
                if (item instanceof ApiResponseSet set) {
                    alerts.add(new InventoryAlertSummary(
                            set.getStringValue("alert"),
                            set.getStringValue("risk"),
                            set.getStringValue("url")
                    ));
                }
            }
            return List.copyOf(alerts);
        } catch (ClientApiException e) {
            log.error("Failed to retrieve alerts for base URL: {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve alerts", e);
        }
    }

    @Override
    public List<String> listHosts() {
        try {
            return responseElementValues((ApiResponseList) zap.core.hosts());
        } catch (ClientApiException e) {
            log.error("Failed to retrieve hosts: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve hosts", e);
        }
    }

    @Override
    public List<String> listSites() {
        try {
            return responseElementValues((ApiResponseList) zap.core.sites());
        } catch (ClientApiException e) {
            log.error("Failed to retrieve sites: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve sites", e);
        }
    }

    @Override
    public List<String> listUrls(String baseUrl) {
        try {
            return responseElementValues((ApiResponseList) zap.core.urls(baseUrl != null ? baseUrl : ""));
        } catch (ClientApiException e) {
            log.error("Failed to retrieve URLs for base URL: {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve URLs", e);
        }
    }

    private List<String> responseElementValues(ApiResponseList responseList) {
        List<String> values = new ArrayList<>();
        for (ApiResponse item : responseList.getItems()) {
            if (item instanceof ApiResponseElement element) {
                values.add(element.getValue());
            }
        }
        return List.copyOf(values);
    }
}
