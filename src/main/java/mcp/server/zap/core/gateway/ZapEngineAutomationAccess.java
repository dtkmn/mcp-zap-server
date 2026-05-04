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
 * ZAP-backed implementation of the automation plan access boundary.
 */
@Slf4j
@Component
public class ZapEngineAutomationAccess implements EngineAutomationAccess {

    private final ClientApi zap;

    public ZapEngineAutomationAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public String runAutomationPlan(String zapPlanPath) {
        try {
            ApiResponse response = zap.automation.runPlan(zapPlanPath);
            return requireResponseElementValue(response, "automation.runPlan()");
        } catch (ClientApiException e) {
            log.error("Error running automation plan {}: {}", zapPlanPath, e.getMessage(), e);
            throw automationException("Error running automation plan. Ensure the ZAP 'automation' add-on is installed.", e);
        }
    }

    @Override
    public AutomationPlanProgress loadAutomationPlanProgress(String planId) {
        try {
            ApiResponse response = zap.automation.planProgress(planId);
            if (!(response instanceof ApiResponseSet responseSet)) {
                throw new IllegalStateException("Unexpected response from automation.planProgress(): " + response);
            }

            return new AutomationPlanProgress(
                    defaultDisplayValue(responseSet.getStringValue("started")),
                    trimToNull(responseSet.getStringValue("finished")),
                    extractStringList(responseSet.getValue("info")),
                    extractStringList(responseSet.getValue("warn")),
                    extractStringList(responseSet.getValue("error"))
            );
        } catch (ClientApiException e) {
            log.error("Error retrieving automation plan progress for {}: {}", planId, e.getMessage(), e);
            throw automationException("Error retrieving automation plan status. Ensure the ZAP 'automation' add-on is installed.", e);
        }
    }

    private List<String> extractStringList(ApiResponse response) {
        if (!(response instanceof ApiResponseList responseList)) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (ApiResponse item : responseList.getItems()) {
            if (item instanceof ApiResponseElement element && hasText(element.getValue())) {
                values.add(element.getValue().trim());
            }
        }
        return List.copyOf(values);
    }

    private String requireResponseElementValue(ApiResponse response, String operation) {
        if (!(response instanceof ApiResponseElement element) || !hasText(element.getValue())) {
            throw new IllegalStateException("Unexpected response from " + operation + ": " + response);
        }
        return element.getValue().trim();
    }

    private ZapApiException automationException(String message, Exception cause) {
        return new ZapApiException(message, cause);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String defaultDisplayValue(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
