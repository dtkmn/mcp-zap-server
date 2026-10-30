package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.NativeScanLaunch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRuleMutation;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.PolicyCategorySnapshot;
import mcp.server.zap.core.gateway.EngineScanExecution.ScannerRuleSnapshot;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for direct and queue-managed active scan operations.
 */
@Service
public class ActiveScanService {
    private static final int DEFAULT_POLICY_VIEW_LIMIT = 25;
    private static final int MAX_POLICY_VIEW_LIMIT = 100;
    private static final int MAX_RULE_MUTATIONS_PER_CALL = 50;
    private static final Set<String> VALID_ATTACK_STRENGTHS =
            Set.of("DEFAULT", "LOW", "MEDIUM", "HIGH", "INSANE");
    private static final Set<String> VALID_ALERT_THRESHOLDS =
            Set.of("DEFAULT", "LOW", "MEDIUM", "HIGH", "OFF");

    private final EngineScanExecution engineScanExecution;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private OperationRegistry operationRegistry;

    /**
     * Build-time dependency injection constructor.
     */
    public ActiveScanService(EngineScanExecution engineScanExecution,
                             UrlValidationService urlValidationService,
                             ScanLimitProperties scanLimitProperties) {
        this.engineScanExecution = engineScanExecution;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    /**
     * Require a non-blank string and return its trimmed value.
     */
    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    /**
     * Check whether a string contains non-whitespace text.
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String listScanPolicies() {
        List<String> policyNames = getAvailableScanPolicyNames();
        if (policyNames.isEmpty()) {
            return "No active-scan policies were returned by ZAP.";
        }

        StringBuilder response = new StringBuilder("Available active-scan policies:\n");
        for (String policyName : policyNames) {
            response.append("- ").append(policyName).append('\n');
        }
        return response.toString();
    }

    public String viewScanPolicy(
            String scanPolicyName,
            String ruleFilter,
            String limit
    ) {
        String normalizedPolicyName = requireKnownScanPolicyName(scanPolicyName);
        int maxRules = validatePolicyViewLimit(limit);
        List<PolicyCategorySnapshot> categories = loadPolicyCategories(normalizedPolicyName);
        List<ScannerRuleSnapshot> allRules = loadPolicyScanners(normalizedPolicyName);
        List<ScannerRuleSnapshot> matchingRules = filterPolicyRules(allRules, ruleFilter);
        return formatScanPolicyView(normalizedPolicyName, categories, allRules, matchingRules, ruleFilter, maxRules);
    }

    public String setScanPolicyRuleState(
            String scanPolicyName,
            String ruleIds,
            String enabled,
            String attackStrength,
            String alertThreshold
    ) {
        String normalizedPolicyName = requireKnownScanPolicyName(scanPolicyName);
        List<String> normalizedRuleIds = parseRuleIds(ruleIds);
        Boolean normalizedEnabled = parseEnabledFlag(enabled);
        String normalizedAttackStrength = normalizeAttackStrength(attackStrength);
        String normalizedAlertThreshold = normalizeAlertThreshold(alertThreshold);

        if (normalizedEnabled == null
                && normalizedAttackStrength == null
                && normalizedAlertThreshold == null) {
            throw new IllegalArgumentException(
                    "At least one of enabled, attackStrength, or alertThreshold must be provided");
        }

        Map<String, ScannerRuleSnapshot> rulesById = loadPolicyScanners(normalizedPolicyName).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ScannerRuleSnapshot::id,
                        rule -> rule,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        List<String> missingRuleIds = normalizedRuleIds.stream()
                .filter(ruleId -> !rulesById.containsKey(ruleId))
                .toList();
        if (!missingRuleIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown rule IDs for policy '" + normalizedPolicyName + "': " + String.join(", ", missingRuleIds));
        }

        engineScanExecution.updateActiveScanRuleState(new ActiveScanRuleMutation(
                normalizedPolicyName,
                normalizedRuleIds,
                normalizedEnabled,
                normalizedAttackStrength,
                normalizedAlertThreshold
        ));
        Map<String, ScannerRuleSnapshot> updatedRulesById = loadPolicyScanners(normalizedPolicyName).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ScannerRuleSnapshot::id,
                        rule -> rule,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
        return formatScanPolicyUpdateResult(
                normalizedPolicyName,
                normalizedRuleIds,
                normalizedEnabled,
                normalizedAttackStrength,
                normalizedAlertThreshold,
                updatedRulesById
        );
    }

    public String getActiveScanStatus(
            String scanId
    ) {
        String normalizedScanId = requireText(scanId, "scanId");
        int progress = getActiveScanProgressPercent(normalizedScanId);
        boolean completed = progress >= 100;
        trackDirectScanStatus(normalizedScanId, completed);
        return String.format(
                "Direct active scan status:%n" +
                        "Scan ID: %s%n" +
                        "Progress: %d%%%n" +
                        "Completed: %s%n" +
                        "%s",
                normalizedScanId,
                progress,
                completed ? "yes" : "no",
                completed
                        ? "Run 'zap_passive_scan_wait' before reading findings or generating reports."
                        : "Use 'zap_active_scan_stop' to stop this direct scan, or 'zap_queue_active_scan' for durable queued execution next time."
        );
    }

    public String stopActiveScan(
            String scanId
    ) {
        String normalizedScanId = requireText(scanId, "scanId");
        stopActiveScanJob(normalizedScanId);
        trackDirectScanStopped(normalizedScanId);
        return "Direct active scan stopped.\n"
                + "Scan ID: " + normalizedScanId + '\n'
                + "For retryable or HA-safe execution, prefer 'zap_queue_active_scan'.";
    }

    /**
     * Start a standard active scan and return the ZAP scan ID.
     */
public String startActiveScanJob(String targetUrl, String recurse, String policy, NativeScanLaunch launch) {
        urlValidationService.validateUrl(targetUrl);
        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectivePolicy = hasText(policy) ? policy.trim() : null;

        ActiveScanRequest request = new ActiveScanRequest(
                targetUrl,
                effectiveRecurse,
                effectivePolicy,
                scanLimitProperties.getMaxActiveScanDurationInMins(),
                scanLimitProperties.getHostPerScan(),
                scanLimitProperties.getThreadPerHost()
        );
        return launch == NativeScanLaunch.UNTRACKED
                ? engineScanExecution.startActiveScan(request)
                : engineScanExecution.startActiveScan(request, launch);
    }

    /**
     * Start an authenticated active scan and return the ZAP scan ID.
     */
public String startActiveScanAsUserJob(String contextId,
                                           String userId,
                                           String targetUrl,
                                           String recurse,
                                           String policy, NativeScanLaunch launch) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");
        urlValidationService.validateUrl(targetUrl);

        String effectiveRecurse = hasText(recurse) ? recurse.trim() : "true";
        String effectivePolicy = hasText(policy) ? policy.trim() : null;

        AuthenticatedActiveScanRequest request = new AuthenticatedActiveScanRequest(
                normalizedContextId,
                normalizedUserId,
                targetUrl,
                effectiveRecurse,
                effectivePolicy,
                scanLimitProperties.getMaxActiveScanDurationInMins(),
                scanLimitProperties.getHostPerScan(),
                scanLimitProperties.getThreadPerHost()
        );
        return launch == NativeScanLaunch.UNTRACKED
                ? engineScanExecution.startActiveScanAsUser(request)
                : engineScanExecution.startActiveScanAsUser(request, launch);
    }

    /**
     * Read current active-scan progress from ZAP.
     */
    public int getActiveScanProgressPercent(String scanId) {
        return engineScanExecution.readActiveScanProgressPercent(scanId);
    }

    /**
     * Stop a running active scan.
     */
    public void stopActiveScanJob(String scanId) {
        engineScanExecution.stopActiveScan(scanId);
    }

    private List<String> getAvailableScanPolicyNames() {
        return engineScanExecution.listActiveScanPolicyNames();
    }

    private String requireKnownScanPolicyName(String scanPolicyName) {
        String normalizedPolicyName = requireText(scanPolicyName, "scanPolicyName");
        for (String knownPolicyName : getAvailableScanPolicyNames()) {
            if (knownPolicyName.equalsIgnoreCase(normalizedPolicyName)) {
                return knownPolicyName;
            }
        }
        throw new IllegalArgumentException(
                "Unknown scan policy '" + normalizedPolicyName + "'. Use zap_scan_policies_list first.");
    }

    private List<PolicyCategorySnapshot> loadPolicyCategories(String scanPolicyName) {
        return engineScanExecution.loadActiveScanPolicyCategories(scanPolicyName);
    }

    private List<ScannerRuleSnapshot> loadPolicyScanners(String scanPolicyName) {
        return engineScanExecution.loadActiveScanPolicyRules(scanPolicyName);
    }

    private List<ScannerRuleSnapshot> filterPolicyRules(List<ScannerRuleSnapshot> rules, String ruleFilter) {
        if (!hasText(ruleFilter)) {
            return rules;
        }

        String normalizedFilter = ruleFilter.trim();
        String loweredFilter = normalizedFilter.toLowerCase(Locale.ROOT);
        return rules.stream()
                .filter(rule -> rule.id().equals(normalizedFilter)
                        || rule.policyId().equals(normalizedFilter)
                        || safeLower(rule.name()).contains(loweredFilter))
                .toList();
    }

    private int validatePolicyViewLimit(String limit) {
        if (!hasText(limit)) {
            return DEFAULT_POLICY_VIEW_LIMIT;
        }

        try {
            int parsed = Integer.parseInt(limit.trim());
            if (parsed < 1 || parsed > MAX_POLICY_VIEW_LIMIT) {
                throw new IllegalArgumentException(
                        "limit must be between 1 and " + MAX_POLICY_VIEW_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number", e);
        }
    }

    private List<String> parseRuleIds(String ruleIds) {
        String normalizedRuleIds = requireText(ruleIds, "ruleIds");
        LinkedHashSet<String> parsedRuleIds = new LinkedHashSet<>();
        for (String token : normalizedRuleIds.split("[,\\s]+")) {
            if (!hasText(token)) {
                continue;
            }
            String trimmedToken = token.trim();
            if (!trimmedToken.matches("\\d+")) {
                throw new IllegalArgumentException(
                        "ruleIds must contain only numeric scanner rule IDs. Invalid value: " + trimmedToken);
            }
            parsedRuleIds.add(trimmedToken);
        }

        if (parsedRuleIds.isEmpty()) {
            throw new IllegalArgumentException("ruleIds must include at least one numeric scanner rule ID");
        }
        if (parsedRuleIds.size() > MAX_RULE_MUTATIONS_PER_CALL) {
            throw new IllegalArgumentException(
                    "ruleIds may include at most " + MAX_RULE_MUTATIONS_PER_CALL + " rules per call");
        }
        return List.copyOf(parsedRuleIds);
    }

    @Nullable
    private Boolean parseEnabledFlag(String enabled) {
        if (!hasText(enabled)) {
            return null;
        }

        String normalizedEnabled = enabled.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedEnabled) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("enabled must be either true or false");
        };
    }

    private String normalizeAttackStrength(String attackStrength) {
        if (!hasText(attackStrength)) {
            return null;
        }

        String normalizedAttackStrength = attackStrength.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ATTACK_STRENGTHS.contains(normalizedAttackStrength)) {
            throw new IllegalArgumentException(
                    "attackStrength must be one of " + String.join(", ", VALID_ATTACK_STRENGTHS));
        }
        return normalizedAttackStrength;
    }

    private String normalizeAlertThreshold(String alertThreshold) {
        if (!hasText(alertThreshold)) {
            return null;
        }

        String normalizedAlertThreshold = alertThreshold.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ALERT_THRESHOLDS.contains(normalizedAlertThreshold)) {
            throw new IllegalArgumentException(
                    "alertThreshold must be one of " + String.join(", ", VALID_ALERT_THRESHOLDS));
        }
        return normalizedAlertThreshold;
    }

    private void trackDirectScanStatus(String scanId, boolean completed) {
        if (operationRegistry == null || !hasText(scanId)) {
            return;
        }
        String operationId = "active:" + scanId;
        if (completed) {
            operationRegistry.releaseDirectScan(operationId);
            return;
        }
        operationRegistry.touchDirectScan(operationId);
    }

    private void trackDirectScanStopped(String scanId) {
        if (operationRegistry == null || !hasText(scanId)) {
            return;
        }
        operationRegistry.releaseDirectScan("active:" + scanId);
    }

    private String formatScanPolicyView(String scanPolicyName,
                                        List<PolicyCategorySnapshot> categories,
                                        List<ScannerRuleSnapshot> allRules,
                                        List<ScannerRuleSnapshot> matchingRules,
                                        String ruleFilter,
                                        int limit) {
        long enabledRules = allRules.stream().filter(ScannerRuleSnapshot::enabled).count();
        long overriddenRules = allRules.stream()
                .filter(ScannerRuleSnapshot::hasOverride)
                .count();
        int shownRules = Math.min(limit, matchingRules.size());
        Map<String, String> categoryNames = categories.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PolicyCategorySnapshot::id,
                        PolicyCategorySnapshot::name,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));

        StringBuilder response = new StringBuilder();
        response.append("Active-scan policy view:\n")
                .append("Policy: ").append(scanPolicyName).append('\n')
                .append("Categories: ").append(categories.size()).append('\n')
                .append("Rules: ").append(allRules.size()).append(" total, ")
                .append(enabledRules).append(" enabled, ")
                .append(overriddenRules).append(" overridden\n");

        if (hasText(ruleFilter)) {
            response.append("Filter: ").append(ruleFilter.trim())
                    .append(" (matched ").append(matchingRules.size()).append(" rules)\n");
        }

        response.append("Showing: ").append(shownRules).append(" of ").append(matchingRules.size()).append(" matched rules\n");

        response.append("\nPolicy categories:\n");
        for (PolicyCategorySnapshot category : categories) {
            response.append("- [").append(category.id()).append("] ")
                    .append(category.name())
                    .append(" | enabled=").append(formatBoolean(category.enabled()))
                    .append(" | attack=").append(category.attackStrength())
                    .append(" | threshold=").append(category.alertThreshold())
                    .append('\n');
        }

        response.append("\nRules:\n");
        if (matchingRules.isEmpty()) {
            response.append("- No rules matched the requested filter.\n");
        } else {
            for (ScannerRuleSnapshot rule : matchingRules.subList(0, shownRules)) {
                response.append("- ")
                        .append(formatScannerRuleLine(rule, categoryNames))
                        .append('\n');
            }
            if (matchingRules.size() > shownRules) {
                response.append("Use a higher limit or a narrower ruleFilter to inspect more rules.\n");
            }
        }

        return response.toString();
    }

    private String formatScanPolicyUpdateResult(String scanPolicyName,
                                                List<String> ruleIds,
                                                Boolean enabled,
                                                String attackStrength,
                                                String alertThreshold,
                                                Map<String, ScannerRuleSnapshot> updatedRulesById) {
        StringBuilder response = new StringBuilder();
        response.append("Active-scan policy updated.\n")
                .append("Policy: ").append(scanPolicyName).append('\n')
                .append("Rule IDs: ").append(String.join(", ", ruleIds)).append('\n');

        if (enabled != null) {
            response.append("Requested enabled state: ").append(formatBoolean(enabled)).append('\n');
        }
        if (attackStrength != null) {
            response.append("Requested attack strength: ").append(attackStrength).append('\n');
        }
        if (alertThreshold != null) {
            response.append("Requested alert threshold: ").append(alertThreshold).append('\n');
        }

        response.append("\nUpdated rules:\n");
        for (String ruleId : ruleIds) {
            ScannerRuleSnapshot rule = updatedRulesById.get(ruleId);
            response.append("- ");
            if (rule == null) {
                response.append(ruleId).append(" | no longer returned by ZAP");
            } else {
                response.append(rule.id())
                        .append(" | ").append(rule.name())
                        .append(" | enabled=").append(formatBoolean(rule.enabled()))
                        .append(" | attack=").append(rule.attackStrength())
                        .append(" | threshold=").append(rule.alertThreshold());
            }
            response.append('\n');
        }

        response.append("Use 'zap_scan_policy_view' to inspect the rest of the policy before starting a guided active scan.");
        return response.toString();
    }

    private String formatScannerRuleLine(ScannerRuleSnapshot rule, Map<String, String> categoryNames) {
        StringBuilder line = new StringBuilder();
        line.append(rule.id())
                .append(" | ").append(rule.name())
                .append(" | category=").append(categoryNames.getOrDefault(rule.policyId(), rule.policyId()))
                .append(" | enabled=").append(formatBoolean(rule.enabled()))
                .append(" | attack=").append(rule.attackStrength())
                .append(" | threshold=").append(rule.alertThreshold());

        if (hasText(rule.quality()) && !"release".equalsIgnoreCase(rule.quality())) {
            line.append(" | quality=").append(rule.quality());
        }
        if (hasText(rule.status()) && !"release".equalsIgnoreCase(rule.status())) {
            line.append(" | status=").append(rule.status());
        }
        if (!rule.dependencies().isEmpty()) {
            line.append(" | dependencies=").append(String.join(",", rule.dependencies()));
        }
        return line.toString();
    }

    private String formatBoolean(boolean value) {
        return value ? "yes" : "no";
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

}
