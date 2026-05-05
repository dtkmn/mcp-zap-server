package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.Alert;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the gateway findings access boundary.
 */
@Slf4j
@Component
public class ZapEngineFindingAccess implements EngineFindingAccess {

    private final ClientApi zap;

    public ZapEngineFindingAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public List<AlertSnapshot> loadAlerts(String baseUrl) {
        try {
            ApiResponse response = zap.alert.alerts(trimToNull(baseUrl), "0", "-1", null, null, null);
            if (!(response instanceof ApiResponseList list)) {
                throw new IllegalStateException("Unexpected response from alert.alerts(): " + response);
            }
            List<AlertSnapshot> alerts = new ArrayList<>();
            for (ApiResponse item : list.getItems()) {
                if (item instanceof ApiResponseSet set) {
                    alerts.add(toSnapshot(new Alert(set)));
                }
            }
            return List.copyOf(alerts);
        } catch (ClientApiException e) {
            log.error("Error retrieving alerts for base URL {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Error retrieving detailed alerts", e);
        }
    }

    private AlertSnapshot toSnapshot(Alert alert) {
        return new AlertSnapshot(
                stringValue(alert.getId()),
                stringValue(alert.getPluginId()),
                stringValue(alert.getName()),
                stringValue(alert.getDescription()),
                stringValue(alert.getRisk()),
                stringValue(alert.getConfidence()),
                stringValue(alert.getUrl()),
                stringValue(alert.getParam()),
                stringValue(alert.getAttack()),
                stringValue(alert.getEvidence()),
                stringValue(alert.getReference()),
                stringValue(alert.getSolution()),
                stringValue(alert.getMessageId()),
                stringValue(alert.getCweId()),
                stringValue(alert.getWascId())
        );
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
