package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.Comparator;
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
 * ZAP-backed implementation of the gateway scan execution boundary.
 */
@Slf4j
@Component
public class ZapEngineScanExecution implements EngineScanExecution {
    private static final int TARGET_ACCESS_MAX_ATTEMPTS = 3;
    private static final long TARGET_ACCESS_RETRY_DELAY_MS = 2000L;

    private final ClientApi zap;

    public ZapEngineScanExecution(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public String startSpiderScan(SpiderScanRequest request) {
        try {
            accessTargetWithRetry(request.targetUrl());
            zap.spider.setOptionThreadCount(request.threadCount());
            zap.spider.setOptionMaxDuration(request.maxDurationMinutes());

            ApiResponse response = zap.spider.scan(
                    request.targetUrl(),
                    String.valueOf(request.maxDepth()),
                    "true",
                    "",
                    "false"
            );
            String scanId = responseValue(response, "spider.scan()");
            log.info("Spider scan started with ID: {} for URL: {}", scanId, request.targetUrl());
            return scanId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Spider scan was interrupted for URL {}", request.targetUrl());
            throw new ZapApiException("Spider scan was interrupted", e);
        } catch (ClientApiException e) {
            log.error("Error launching ZAP Spider for URL {}: {}", request.targetUrl(), e.getMessage(), e);
            String errorMessage = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (errorMessage.contains("unexpected end of file") || errorMessage.contains("socketexception")) {
                throw new ZapApiException("Target website is blocking ZAP requests or closed the connection. "
                        + "Common causes: WAF protection, bot detection, IP blocking. "
                        + "Try testing with juice-shop (http://juice-shop:3000) or petstore (http://petstore:8080) instead. "
                        + "Original error: " + e.getMessage(), e);
            }
            throw new ZapApiException("Error launching ZAP Spider for URL "
                    + request.targetUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String startSpiderScanAsUser(AuthenticatedSpiderScanRequest request) {
        try {
            zap.spider.setOptionThreadCount(request.threadCount());
            zap.spider.setOptionMaxDuration(request.maxDurationMinutes());

            ApiResponse response = zap.spider.scanAsUser(
                    request.contextId(),
                    request.userId(),
                    request.targetUrl(),
                    request.maxChildren(),
                    request.recurse(),
                    request.subtreeOnly()
            );

            String scanId = responseValue(response, "spider.scanAsUser()");
            log.info("Spider-as-user started with ID: {} for URL: {}, context: {}, user: {}",
                    scanId, request.targetUrl(), request.contextId(), request.userId());
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error launching spider-as-user for URL {}: {}", request.targetUrl(), e.getMessage(), e);
            throw new ZapApiException("Error launching spider-as-user for URL "
                    + request.targetUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int readSpiderProgressPercent(String scanId) {
        try {
            ApiResponse response = zap.spider.status(scanId);
            return parseProgressPercent(response, "spider.status()", scanId);
        } catch (ClientApiException e) {
            log.error("Error retrieving spider status for ID {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving spider status for ID " + scanId, e);
        }
    }

    @Override
    public void stopSpiderScan(String scanId) {
        try {
            zap.spider.stop(scanId);
        } catch (ClientApiException e) {
            log.error("Error stopping spider scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping spider scan " + scanId, e);
        }
    }

    @Override
    public String startActiveScan(ActiveScanRequest request) {
        try {
            configureActiveScan(request.maxDurationMinutes(), request.hostPerScan(), request.threadPerHost());
            ApiResponseElement response = (ApiResponseElement) zap.ascan.scan(
                    request.targetUrl(),
                    request.recurse(),
                    "false",
                    request.policy(),
                    null,
                    null
            );
            String scanId = requireElementValue(
                    response,
                    "ascan.scan()",
                    "Failed to start scan on " + request.targetUrl() + ": received null response"
            );
            log.info("Started active scan with ID {} on {} with policy: {}, maxDuration: {} mins",
                    scanId, request.targetUrl(), request.policy(), request.maxDurationMinutes());
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error starting active scan on {}: {}", request.targetUrl(), e.getMessage(), e);
            throw new ZapApiException("Error starting active scan on "
                    + request.targetUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String startActiveScanAsUser(AuthenticatedActiveScanRequest request) {
        try {
            configureActiveScan(request.maxDurationMinutes(), request.hostPerScan(), request.threadPerHost());
            ApiResponseElement response = (ApiResponseElement) zap.ascan.scanAsUser(
                    request.targetUrl(),
                    request.contextId(),
                    request.userId(),
                    request.recurse(),
                    request.policy(),
                    null,
                    null
            );
            String scanId = requireElementValue(
                    response,
                    "ascan.scanAsUser()",
                    "Failed to start scan-as-user on " + request.targetUrl() + ": received null response"
            );
            log.info("Started active scan-as-user with ID {} on {} for context {}, user {}",
                    scanId, request.targetUrl(), request.contextId(), request.userId());
            return scanId;
        } catch (ClientApiException e) {
            log.error("Error starting active scan-as-user on {}: {}", request.targetUrl(), e.getMessage(), e);
            throw new ZapApiException("Error starting active scan-as-user on "
                    + request.targetUrl() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int readActiveScanProgressPercent(String scanId) {
        try {
            ApiResponse response = zap.ascan.status(scanId);
            return parseProgressPercent(response, "ascan.status()", scanId);
        } catch (ClientApiException e) {
            log.error("Error retrieving active scan status for {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error retrieving status for active scan " + scanId, e);
        }
    }

    @Override
    public void stopActiveScan(String scanId) {
        try {
            zap.ascan.stop(scanId);
        } catch (ClientApiException e) {
            log.error("Error stopping active scan {}: {}", scanId, e.getMessage(), e);
            throw new ZapApiException("Error stopping active scan " + scanId, e);
        }
    }

    @Override
    public List<String> listActiveScanPolicyNames() {
        try {
            ApiResponse response = zap.ascan.scanPolicyNames();
            ApiResponseList responseList = requireResponseList(response, "ascan.scanPolicyNames()");
            List<String> policyNames = new ArrayList<>();
            for (ApiResponse item : responseList.getItems()) {
                if (item instanceof ApiResponseElement element && hasText(element.getValue())) {
                    policyNames.add(element.getValue().trim());
                }
            }
            return List.copyOf(policyNames);
        } catch (ClientApiException e) {
            log.error("Error listing active-scan policies: {}", e.getMessage(), e);
            throw new ZapApiException("Error listing active-scan policies", e);
        }
    }

    @Override
    public List<PolicyCategorySnapshot> loadActiveScanPolicyCategories(String scanPolicyName) {
        try {
            ApiResponse response = zap.ascan.policies(scanPolicyName, null);
            ApiResponseList responseList = requireResponseList(response, "ascan.policies()");
            List<PolicyCategorySnapshot> categories = new ArrayList<>();
            for (ApiResponse item : responseList.getItems()) {
                if (item instanceof ApiResponseSet responseSet) {
                    categories.add(new PolicyCategorySnapshot(
                            responseSet.getStringValue("id"),
                            responseSet.getStringValue("name"),
                            isAffirmative(responseSet.getStringValue("enabled")),
                            defaultDisplayValue(responseSet.getStringValue("attackStrength")),
                            defaultDisplayValue(responseSet.getStringValue("alertThreshold"))
                    ));
                }
            }
            categories.sort(Comparator.comparingInt(category -> parseNumericOrder(category.id())));
            return List.copyOf(categories);
        } catch (ClientApiException e) {
            log.error("Error retrieving policy categories for {}: {}", scanPolicyName, e.getMessage(), e);
            throw new ZapApiException("Error retrieving policy details for " + scanPolicyName, e);
        }
    }

    @Override
    public List<ScannerRuleSnapshot> loadActiveScanPolicyRules(String scanPolicyName) {
        try {
            ApiResponse response = zap.ascan.scanners(scanPolicyName, null);
            ApiResponseList responseList = requireResponseList(response, "ascan.scanners()");
            List<ScannerRuleSnapshot> rules = new ArrayList<>();
            for (ApiResponse item : responseList.getItems()) {
                if (item instanceof ApiResponseSet responseSet) {
                    rules.add(new ScannerRuleSnapshot(
                            responseSet.getStringValue("id"),
                            responseSet.getStringValue("name"),
                            responseSet.getStringValue("policyId"),
                            isAffirmative(responseSet.getStringValue("enabled")),
                            defaultDisplayValue(responseSet.getStringValue("attackStrength")),
                            defaultDisplayValue(responseSet.getStringValue("alertThreshold")),
                            defaultDisplayValue(responseSet.getStringValue("quality")),
                            defaultDisplayValue(responseSet.getStringValue("status")),
                            parseResponseListValues(responseSet.getValue("dependencies"))
                    ));
                }
            }
            rules.sort(
                    Comparator.comparingInt((ScannerRuleSnapshot rule) -> parseNumericOrder(rule.policyId()))
                            .thenComparing(rule -> safeLower(rule.name()))
                            .thenComparingInt(rule -> parseNumericOrder(rule.id()))
            );
            return List.copyOf(rules);
        } catch (ClientApiException e) {
            log.error("Error retrieving policy rules for {}: {}", scanPolicyName, e.getMessage(), e);
            throw new ZapApiException("Error retrieving policy rules for " + scanPolicyName, e);
        }
    }

    @Override
    public void updateActiveScanRuleState(ActiveScanRuleMutation mutation) {
        try {
            String joinedRuleIds = String.join(",", mutation.ruleIds());
            if (mutation.enabled() != null) {
                if (mutation.enabled()) {
                    zap.ascan.enableScanners(joinedRuleIds, mutation.scanPolicyName());
                } else {
                    zap.ascan.disableScanners(joinedRuleIds, mutation.scanPolicyName());
                }
            }

            for (String ruleId : mutation.ruleIds()) {
                if (mutation.attackStrength() != null) {
                    zap.ascan.setScannerAttackStrength(ruleId, mutation.attackStrength(), mutation.scanPolicyName());
                }
                if (mutation.alertThreshold() != null) {
                    zap.ascan.setScannerAlertThreshold(ruleId, mutation.alertThreshold(), mutation.scanPolicyName());
                }
            }
        } catch (ClientApiException e) {
            log.error("Error updating scan policy {} for rules {}: {}",
                    mutation.scanPolicyName(), mutation.ruleIds(), e.getMessage(), e);
            throw new ZapApiException(
                    "Error updating scan policy " + mutation.scanPolicyName()
                            + " for rules " + String.join(", ", mutation.ruleIds()),
                    e
            );
        }
    }

    private void accessTargetWithRetry(String targetUrl) throws InterruptedException {
        ClientApiException lastFailure = null;
        for (int attempt = 1; attempt <= TARGET_ACCESS_MAX_ATTEMPTS; attempt++) {
            try {
                zap.core.accessUrl(targetUrl, "true");
                return;
            } catch (ClientApiException e) {
                lastFailure = e;
                if (attempt < TARGET_ACCESS_MAX_ATTEMPTS) {
                    log.warn("Retry {}/{} - Failed to access URL {}: {}",
                            attempt, TARGET_ACCESS_MAX_ATTEMPTS, targetUrl, e.getMessage());
                    Thread.sleep(TARGET_ACCESS_RETRY_DELAY_MS);
                }
            }
        }

        if (lastFailure == null) {
            throw new ZapApiException("Target website could not be accessed because no ZAP access attempt was made",
                    new IllegalStateException("No ZAP access attempts were made"));
        }

        log.error("Failed to access URL after {} attempts: {}",
                TARGET_ACCESS_MAX_ATTEMPTS, lastFailure.getMessage());
        throw new ZapApiException("Target website is blocking ZAP requests or is unreachable. "
                + "This could be due to WAF protection, IP blocking, or network issues. "
                + "Original error: " + lastFailure.getMessage(), lastFailure);
    }

    private void configureActiveScan(int maxDurationMinutes, int hostPerScan, int threadPerHost)
            throws ClientApiException {
        zap.ascan.enableAllScanners(null);
        zap.ascan.setOptionMaxScanDurationInMins(maxDurationMinutes);
        zap.ascan.setOptionHostPerScan(hostPerScan);
        zap.ascan.setOptionThreadPerHost(threadPerHost);
    }

    private String responseValue(ApiResponse response, String operation) {
        if (!(response instanceof ApiResponseElement element)) {
            throw new IllegalStateException("Unexpected response from " + operation + ": " + response);
        }
        return element.getValue();
    }

    private String requireElementValue(ApiResponseElement response, String operation, String nullResponseMessage) {
        if (response == null) {
            throw new IllegalStateException(nullResponseMessage);
        }
        return responseValue(response, operation);
    }

    private int parseProgressPercent(ApiResponse response, String operation, String scanId) {
        String value = responseValue(response, operation);
        if (!hasText(value)) {
            throw new ZapApiException("Unexpected blank progress value from " + operation
                    + " for scan " + scanId, new IllegalStateException("Blank ZAP progress response"));
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Unexpected non-numeric progress value from {} for scan {}: {}", operation, scanId, value);
            throw new ZapApiException("Unexpected non-numeric progress value from " + operation
                    + " for scan " + scanId, e);
        }
    }

    private ApiResponseList requireResponseList(ApiResponse response, String operation) {
        if (!(response instanceof ApiResponseList responseList)) {
            throw new IllegalStateException("Unexpected response from " + operation + ": " + response);
        }
        return responseList;
    }

    private List<String> parseResponseListValues(ApiResponse response) {
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

    private boolean isAffirmative(String value) {
        return value != null && value.equalsIgnoreCase("true");
    }

    private String defaultDisplayValue(String value) {
        return hasText(value) ? value.trim() : "<unknown>";
    }

    private int parseNumericOrder(String value) {
        if (!hasText(value)) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
