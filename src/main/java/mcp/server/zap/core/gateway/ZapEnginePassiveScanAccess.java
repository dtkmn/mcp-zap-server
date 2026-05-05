package mcp.server.zap.core.gateway;

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
 * ZAP-backed implementation of the passive scan access boundary.
 */
@Slf4j
@Component
public class ZapEnginePassiveScanAccess implements EnginePassiveScanAccess {

    private final ClientApi zap;

    public ZapEnginePassiveScanAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public PassiveScanSnapshot loadPassiveScanSnapshot() {
        try {
            int recordsToScan = readIntElement(zap.pscan.recordsToScan(), "recordsToScan");
            boolean scanOnlyInScope = Boolean.parseBoolean(readElementValue(zap.pscan.scanOnlyInScope(), "scanOnlyInScope"));
            int activeTasks = readCurrentTaskCount();
            return new PassiveScanSnapshot(recordsToScan, activeTasks, scanOnlyInScope);
        } catch (ClientApiException e) {
            log.error("Error retrieving passive scan status: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving passive scan status", e);
        }
    }

    private int readCurrentTaskCount() {
        try {
            ApiResponse response = zap.pscan.currentTasks();
            if (response instanceof ApiResponseList list) {
                return list.getItems().size();
            }
            if (response instanceof ApiResponseSet set) {
                return set.getValues().size();
            }
            if (response instanceof ApiResponseElement element) {
                return Integer.parseInt(element.getValue());
            }
            return -1;
        } catch (NumberFormatException e) {
            log.debug("Unable to parse passive scan currentTasks response; reporting as unknown", e);
            return -1;
        } catch (ClientApiException e) {
            log.debug("Passive scan currentTasks view unavailable; reporting active task count as unknown: {}", e.getMessage());
            return -1;
        }
    }

    private int readIntElement(ApiResponse response, String operationName) {
        try {
            return Integer.parseInt(readElementValue(response, operationName));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Unexpected numeric response from pscan." + operationName, e);
        }
    }

    private String readElementValue(ApiResponse response, String operationName) {
        if (!(response instanceof ApiResponseElement element)) {
            throw new IllegalStateException("Unexpected response from pscan." + operationName + "(): " + response);
        }
        return element.getValue();
    }
}
