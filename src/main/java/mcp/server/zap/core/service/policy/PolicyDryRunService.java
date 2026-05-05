package mcp.server.zap.core.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mcp.server.zap.core.logging.RequestCorrelationHolder;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Validates and dry-runs Policy Bundle v1 documents without enforcing them.
 */
@Service
public class PolicyDryRunService implements PolicyBundlePreviewer {
    public static final String DRY_RUN_CONTRACT_VERSION = "mcp.zap.policy.dry-run/v1";

    private static final Set<String> RULE_DECISIONS = Set.of("allow", "deny");
    private static final Set<String> DAY_NAMES = Set.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("apiVersion", "kind", "metadata", "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name", "displayName", "description", "owner", "labels");
    private static final Set<String> SPEC_KEYS = Set.of("defaultDecision", "evaluationOrder", "timezone", "rules");
    private static final Set<String> RULE_KEYS = Set.of("id", "description", "decision", "reason", "enabled", "match");
    private static final Set<String> MATCH_KEYS = Set.of("tools", "hosts", "timeWindows");
    private static final Set<String> WINDOW_KEYS = Set.of("days", "start", "end");

    private final ObjectMapper objectMapper;
    private final Set<String> knownActionNames;
    private final ObservabilityService observabilityService;
    private PolicyBundleAccessBoundary policyBundleAccessBoundary;

    @Autowired
    public PolicyDryRunService(ObjectProvider<ObjectMapper> objectMapperProvider,
                               ToolScopeRegistry toolScopeRegistry,
                               ObjectProvider<ObservabilityService> observabilityServiceProvider) {
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.knownActionNames = new LinkedHashSet<>(toolScopeRegistry.getRequiredScopesByTool().keySet());
        this.knownActionNames.add(ToolScopeRegistry.TOOLS_LIST_ACTION);
        this.observabilityService = observabilityServiceProvider.getIfAvailable();
    }

    public PolicyDryRunService(ObjectMapper objectMapper, ToolScopeRegistry toolScopeRegistry) {
        this(objectMapper, toolScopeRegistry, null);
    }

    public PolicyDryRunService(ObjectMapper objectMapper,
                               ToolScopeRegistry toolScopeRegistry,
                               ObservabilityService observabilityService) {
        this.objectMapper = objectMapper;
        this.knownActionNames = new LinkedHashSet<>(toolScopeRegistry.getRequiredScopesByTool().keySet());
        this.knownActionNames.add(ToolScopeRegistry.TOOLS_LIST_ACTION);
        this.observabilityService = observabilityService;
    }

    @Autowired(required = false)
    void setPolicyBundleAccessBoundary(PolicyBundleAccessBoundary policyBundleAccessBoundary) {
        this.policyBundleAccessBoundary = policyBundleAccessBoundary;
    }

    public Map<String, Object> dryRun(String policyBundle,
                                      String toolName,
                                      String target,
                                      String evaluatedAt) {
        Map<String, Object> response = preview(policyBundle, toolName, target, evaluatedAt);
        publishDecisionAudit(response);
        return response;
    }

    @Override
    public Map<String, Object> preview(String policyBundle,
                                       String toolName,
                                       String target,
                                       String evaluatedAt) {
        List<String> validationErrors = new ArrayList<>();
        String normalizedTool = normalizeToolName(toolName, validationErrors);
        NormalizedTarget normalizedTarget = normalizeTarget(target, validationErrors);
        Instant evaluationInstant = parseEvaluationInstant(evaluatedAt, validationErrors);
        ParseOutcome parseOutcome = parseBundle(policyBundle);
        validationErrors.addAll(parseOutcome.errors());
        if (parseOutcome.bundle() != null && policyBundleAccessBoundary != null) {
            validationErrors.addAll(policyBundleAccessBoundary.validateCurrentRequesterAccess(
                    parseOutcome.bundle().metadata().labels()
            ));
        }

        if (!validationErrors.isEmpty()) {
            Map<String, Object> response = invalidResponse(
                    parseOutcome.bundle(),
                    normalizedTool,
                    normalizedTarget,
                    evaluationInstant,
                    validationErrors
            );
            return response;
        }

        return evaluate(parseOutcome.bundle(), normalizedTool, normalizedTarget, evaluationInstant);
    }

    private ParseOutcome parseBundle(String policyBundle) {
        List<String> errors = new ArrayList<>();
        if (policyBundle == null || policyBundle.isBlank()) {
            errors.add("policyBundle must be a non-empty JSON document");
            return new ParseOutcome(null, errors);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(policyBundle);
        } catch (Exception e) {
            errors.add("policyBundle must be valid JSON: " + e.getMessage());
            return new ParseOutcome(null, errors);
        }

        if (root == null || !root.isObject()) {
            errors.add("policyBundle must be a JSON object");
            return new ParseOutcome(null, errors);
        }

        requireExactKeys(root, TOP_LEVEL_KEYS, "bundle", errors);

        String apiVersion = requiredText(root, "apiVersion", "bundle", errors);
        if (apiVersion != null && !"mcp.zap.policy/v1".equals(apiVersion)) {
            errors.add("bundle.apiVersion must equal 'mcp.zap.policy/v1'");
        }

        String kind = requiredText(root, "kind", "bundle", errors);
        if (kind != null && !"PolicyBundle".equals(kind)) {
            errors.add("bundle.kind must equal 'PolicyBundle'");
        }

        JsonNode metadataNode = root.get("metadata");
        PolicyBundleMetadata metadata = parseMetadata(metadataNode, errors);

        JsonNode specNode = root.get("spec");
        PolicyBundleSpec spec = parseSpec(specNode, errors);

        if (!errors.isEmpty()) {
            return new ParseOutcome(null, errors);
        }

        return new ParseOutcome(new PolicyBundle(apiVersion, kind, metadata, spec), errors);
    }

    private PolicyBundleMetadata parseMetadata(JsonNode metadataNode, List<String> errors) {
        if (metadataNode == null || !metadataNode.isObject()) {
            errors.add("bundle.metadata must be an object");
            return null;
        }

        requireExactKeys(metadataNode, METADATA_KEYS, "bundle.metadata", errors);

        String name = requiredText(metadataNode, "name", "bundle.metadata", errors);
        if (name != null && !isKebabCase(name)) {
            errors.add("bundle.metadata.name must be lowercase kebab-case");
        }

        String displayName = optionalText(metadataNode, "displayName", "bundle.metadata", errors);
        String description = requiredText(metadataNode, "description", "bundle.metadata", errors);
        String owner = requiredText(metadataNode, "owner", "bundle.metadata", errors);

        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode labelsNode = metadataNode.get("labels");
        if (labelsNode != null && !labelsNode.isNull()) {
            if (!labelsNode.isObject()) {
                errors.add("bundle.metadata.labels must be an object");
            } else {
                labelsNode.properties().forEach(entry -> {
                    String key = entry.getKey();
                    if (!isKebabCase(key)) {
                        errors.add("bundle.metadata.labels key '" + key + "' must be lowercase kebab-case");
                    }
                    if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                        errors.add("bundle.metadata.labels['" + key + "'] must be a non-empty string");
                    } else {
                        labels.put(key, entry.getValue().asText().trim());
                    }
                });
            }
        }

        return new PolicyBundleMetadata(name, displayName, description, owner, labels);
    }

    private PolicyBundleSpec parseSpec(JsonNode specNode, List<String> errors) {
        if (specNode == null || !specNode.isObject()) {
            errors.add("bundle.spec must be an object");
            return null;
        }

        requireExactKeys(specNode, SPEC_KEYS, "bundle.spec", errors);

        String defaultDecision = requiredText(specNode, "defaultDecision", "bundle.spec", errors);
        if (defaultDecision != null && !RULE_DECISIONS.contains(defaultDecision)) {
            errors.add("bundle.spec.defaultDecision must be 'allow' or 'deny'");
        }

        String evaluationOrder = requiredText(specNode, "evaluationOrder", "bundle.spec", errors);
        if (evaluationOrder != null && !"first-match".equals(evaluationOrder)) {
            errors.add("bundle.spec.evaluationOrder must equal 'first-match'");
        }

        String timezoneId = requiredText(specNode, "timezone", "bundle.spec", errors);
        ZoneId timezone = null;
        if (timezoneId != null) {
            try {
                timezone = ZoneId.of(timezoneId);
            } catch (Exception e) {
                errors.add("bundle.spec.timezone must be a valid timezone ID");
            }
        }

        JsonNode rulesNode = specNode.get("rules");
        if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()) {
            errors.add("bundle.spec.rules must be a non-empty array");
            return null;
        }
        if (rulesNode.size() > 50) {
            errors.add("bundle.spec.rules must not contain more than 50 rules");
        }

        List<PolicyRule> rules = new ArrayList<>();
        Set<String> seenRuleIds = new LinkedHashSet<>();
        for (int index = 0; index < rulesNode.size(); index++) {
            JsonNode ruleNode = rulesNode.get(index);
            PolicyRule rule = parseRule(ruleNode, index, seenRuleIds, errors);
            if (rule != null) {
                rules.add(rule);
            }
        }

        return new PolicyBundleSpec(defaultDecision, evaluationOrder, timezoneId, timezone, rules);
    }

    private PolicyRule parseRule(JsonNode ruleNode,
                                 int index,
                                 Set<String> seenRuleIds,
                                 List<String> errors) {
        String prefix = "bundle.spec.rules[" + index + "]";
        if (ruleNode == null || !ruleNode.isObject()) {
            errors.add(prefix + " must be an object");
            return null;
        }

        requireExactKeys(ruleNode, RULE_KEYS, prefix, errors);

        String id = requiredText(ruleNode, "id", prefix, errors);
        if (id != null && !isKebabCase(id)) {
            errors.add(prefix + ".id must be lowercase kebab-case");
        }
        if (id != null && !seenRuleIds.add(id)) {
            errors.add(prefix + ".id '" + id + "' must be unique");
        }

        String description = requiredText(ruleNode, "description", prefix, errors);
        String decision = requiredText(ruleNode, "decision", prefix, errors);
        if (decision != null && !RULE_DECISIONS.contains(decision)) {
            errors.add(prefix + ".decision must be 'allow' or 'deny'");
        }
        String reason = requiredText(ruleNode, "reason", prefix, errors);

        boolean enabled = true;
        JsonNode enabledNode = ruleNode.get("enabled");
        if (enabledNode != null && !enabledNode.isNull()) {
            if (!enabledNode.isBoolean()) {
                errors.add(prefix + ".enabled must be a boolean");
            } else {
                enabled = enabledNode.asBoolean();
            }
        }

        PolicyMatch match = parseMatch(ruleNode.get("match"), prefix + ".match", errors);
        return new PolicyRule(id, description, decision, reason, enabled, match);
    }

    private PolicyMatch parseMatch(JsonNode matchNode, String prefix, List<String> errors) {
        if (matchNode == null || !matchNode.isObject()) {
            errors.add(prefix + " must be an object");
            return null;
        }

        requireExactKeys(matchNode, MATCH_KEYS, prefix, errors);

        JsonNode toolsNode = matchNode.get("tools");
        JsonNode hostsNode = matchNode.get("hosts");
        JsonNode timeWindowsNode = matchNode.get("timeWindows");
        if ((toolsNode == null || toolsNode.isNull())
                && (hostsNode == null || hostsNode.isNull())
                && (timeWindowsNode == null || timeWindowsNode.isNull())) {
            errors.add(prefix + " must include at least one selector dimension");
        }

        List<String> tools = parseTools(toolsNode, prefix + ".tools", errors);
        List<String> hosts = parseHosts(hostsNode, prefix + ".hosts", errors);
        List<PolicyTimeWindow> timeWindows = parseTimeWindows(timeWindowsNode, prefix + ".timeWindows", errors);

        return new PolicyMatch(tools, hosts, timeWindows);
    }

    private List<String> parseTools(JsonNode toolsNode, String prefix, List<String> errors) {
        if (toolsNode == null || toolsNode.isNull()) {
            return List.of();
        }
        if (!toolsNode.isArray() || toolsNode.isEmpty()) {
            errors.add(prefix + " must be a non-empty array");
            return List.of();
        }

        List<String> tools = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < toolsNode.size(); index++) {
            JsonNode toolNode = toolsNode.get(index);
            if (!toolNode.isTextual() || toolNode.asText().isBlank()) {
                errors.add(prefix + "[" + index + "] must be a non-empty string");
                continue;
            }
            String tool = toolNode.asText().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(tool)) {
                errors.add(prefix + " must not contain duplicate tools");
                continue;
            }
            if (!knownActionNames.contains(tool)) {
                errors.add(prefix + " contains unknown tool '" + tool + "'");
            }
            tools.add(tool);
        }
        return tools;
    }

    private List<String> parseHosts(JsonNode hostsNode, String prefix, List<String> errors) {
        if (hostsNode == null || hostsNode.isNull()) {
            return List.of();
        }
        if (!hostsNode.isArray() || hostsNode.isEmpty()) {
            errors.add(prefix + " must be a non-empty array");
            return List.of();
        }

        List<String> hosts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < hostsNode.size(); index++) {
            JsonNode hostNode = hostsNode.get(index);
            if (!hostNode.isTextual() || hostNode.asText().isBlank()) {
                errors.add(prefix + "[" + index + "] must be a non-empty string");
                continue;
            }
            String host = hostNode.asText().trim().toLowerCase(Locale.ROOT);
            if (host.contains("://") || host.contains("/") || host.contains("?") || host.contains("#") || host.contains(":")) {
                errors.add(prefix + " must use hostnames only, without scheme, path, query, fragment, or port");
                continue;
            }
            if (!isHostPattern(host)) {
                errors.add(prefix + " contains invalid host pattern '" + host + "'");
                continue;
            }
            if (!seen.add(host)) {
                errors.add(prefix + " must not contain duplicate hosts");
                continue;
            }
            hosts.add(host);
        }
        return hosts;
    }

    private List<PolicyTimeWindow> parseTimeWindows(JsonNode timeWindowsNode, String prefix, List<String> errors) {
        if (timeWindowsNode == null || timeWindowsNode.isNull()) {
            return List.of();
        }
        if (!timeWindowsNode.isArray() || timeWindowsNode.isEmpty()) {
            errors.add(prefix + " must be a non-empty array");
            return List.of();
        }

        List<PolicyTimeWindow> windows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < timeWindowsNode.size(); index++) {
            String windowPrefix = prefix + "[" + index + "]";
            JsonNode windowNode = timeWindowsNode.get(index);
            if (windowNode == null || !windowNode.isObject()) {
                errors.add(windowPrefix + " must be an object");
                continue;
            }
            requireExactKeys(windowNode, WINDOW_KEYS, windowPrefix, errors);

            JsonNode daysNode = windowNode.get("days");
            if (daysNode == null || !daysNode.isArray() || daysNode.isEmpty()) {
                errors.add(windowPrefix + ".days must be a non-empty array");
                continue;
            }

            List<String> dayNames = new ArrayList<>();
            Set<String> seenDays = new LinkedHashSet<>();
            for (int dayIndex = 0; dayIndex < daysNode.size(); dayIndex++) {
                JsonNode dayNode = daysNode.get(dayIndex);
                if (!dayNode.isTextual() || dayNode.asText().isBlank()) {
                    errors.add(windowPrefix + ".days[" + dayIndex + "] must be a non-empty string");
                    continue;
                }
                String day = dayNode.asText().trim().toLowerCase(Locale.ROOT);
                if (!DAY_NAMES.contains(day)) {
                    errors.add(windowPrefix + ".days contains invalid value '" + day + "'");
                    continue;
                }
                if (!seenDays.add(day)) {
                    errors.add(windowPrefix + ".days must not contain duplicates");
                    continue;
                }
                dayNames.add(day);
            }

            String start = requiredText(windowNode, "start", windowPrefix, errors);
            String end = requiredText(windowNode, "end", windowPrefix, errors);
            LocalTime startTime = parseLocalTime(start, windowPrefix + ".start", errors);
            LocalTime endTime = parseLocalTime(end, windowPrefix + ".end", errors);
            if (startTime != null && endTime != null && startTime.equals(endTime)) {
                errors.add(windowPrefix + " start and end cannot be identical");
            }

            String signature = dayNames + "|" + start + "|" + end;
            if (!seen.add(signature)) {
                errors.add(prefix + " must not contain duplicate time windows");
                continue;
            }

            List<DayOfWeek> days = dayNames.stream().map(this::parseDayOfWeek).toList();
            windows.add(new PolicyTimeWindow(days, start, startTime, end, endTime));
        }
        return windows;
    }

    private Map<String, Object> invalidResponse(PolicyBundle bundle,
                                                String normalizedTool,
                                                NormalizedTarget normalizedTarget,
                                                Instant evaluationInstant,
                                                List<String> validationErrors) {
        Map<String, Object> response = baseResponse(bundle, normalizedTool, normalizedTarget, evaluationInstant, false, validationErrors);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("result", "invalid");
        decision.put("source", "validation");
        decision.put("matchedRuleId", null);
        decision.put("reason", "The request or policy bundle is invalid.");
        response.put("decision", decision);
        response.put("trace", List.of());
        return response;
    }

    private Map<String, Object> evaluate(PolicyBundle bundle,
                                         String toolName,
                                         NormalizedTarget normalizedTarget,
                                         Instant evaluationInstant) {
        ZonedDateTime bundleTime = evaluationInstant.atZone(bundle.spec().timezone());
        List<Map<String, Object>> trace = new ArrayList<>();

        PolicyRule matchedRule = null;
        for (PolicyRule rule : bundle.spec().rules()) {
            RuleEvaluation evaluation = evaluateRule(rule, toolName, normalizedTarget.normalizedHost(), bundleTime);
            trace.add(traceMap(evaluation));
            if (evaluation.matched()) {
                matchedRule = rule;
                break;
            }
        }

        Map<String, Object> response = baseResponse(bundle, toolName, normalizedTarget, evaluationInstant, true, List.of());
        response.put("trace", trace);

        Map<String, Object> decision = new LinkedHashMap<>();
        if (matchedRule != null) {
            decision.put("result", matchedRule.decision());
            decision.put("source", "rule");
            decision.put("matchedRuleId", matchedRule.id());
            decision.put("reason", matchedRule.reason());
        } else {
            decision.put("result", bundle.spec().defaultDecision());
            decision.put("source", "default");
            decision.put("matchedRuleId", null);
            decision.put("reason", "No enabled rule matched the request. Using bundle default decision.");
        }
        decision.put("defaultDecision", bundle.spec().defaultDecision());
        response.put("decision", decision);
        return response;
    }

    private RuleEvaluation evaluateRule(PolicyRule rule,
                                        String toolName,
                                        String normalizedHost,
                                        ZonedDateTime bundleTime) {
        List<String> matchedSelectors = new ArrayList<>();
        List<String> failedSelectors = new ArrayList<>();

        if (!rule.enabled()) {
            failedSelectors.add("disabled");
            return new RuleEvaluation(rule, false, matchedSelectors, failedSelectors);
        }

        if (!rule.match().tools().isEmpty()) {
            if (rule.match().tools().contains(toolName)) {
                matchedSelectors.add("tools");
            } else {
                failedSelectors.add("tools");
            }
        }

        if (!rule.match().hosts().isEmpty()) {
            if (normalizedHost != null && matchesAnyHost(normalizedHost, rule.match().hosts())) {
                matchedSelectors.add("hosts");
            } else {
                failedSelectors.add("hosts");
            }
        }

        if (!rule.match().timeWindows().isEmpty()) {
            if (matchesAnyWindow(bundleTime, rule.match().timeWindows())) {
                matchedSelectors.add("timeWindows");
            } else {
                failedSelectors.add("timeWindows");
            }
        }

        return new RuleEvaluation(rule, failedSelectors.isEmpty(), matchedSelectors, failedSelectors);
    }

    private Map<String, Object> traceMap(RuleEvaluation evaluation) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("ruleId", evaluation.rule().id());
        trace.put("decision", evaluation.rule().decision());
        trace.put("enabled", evaluation.rule().enabled());
        trace.put("matched", evaluation.matched());
        trace.put("matchedSelectors", evaluation.matchedSelectors());
        trace.put("failedSelectors", evaluation.failedSelectors());
        trace.put("reason", evaluation.rule().reason());
        return trace;
    }

    private Map<String, Object> baseResponse(PolicyBundle bundle,
                                             String toolName,
                                             NormalizedTarget normalizedTarget,
                                             Instant evaluationInstant,
                                             boolean valid,
                                             List<String> validationErrors) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contractVersion", DRY_RUN_CONTRACT_VERSION);
        Map<String, Object> bundleSummary = bundleSummary(bundle);
        Map<String, Object> requestSummary = requestSummary(bundle, toolName, normalizedTarget, evaluationInstant);
        if (policyBundleAccessBoundary != null) {
            policyBundleAccessBoundary.enrichBundleSummary(
                    bundleSummary,
                    bundle == null ? Map.of() : bundle.metadata().labels()
            );
            policyBundleAccessBoundary.enrichRequestSummary(requestSummary);
        }
        response.put("bundle", bundleSummary);
        response.put("request", requestSummary);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("valid", valid);
        validation.put("errors", validationErrors);
        response.put("validation", validation);
        return response;
    }

    private void publishDecisionAudit(Map<String, Object> response) {
        if (observabilityService == null || response == null) {
            return;
        }

        Map<String, Object> bundle = mapValue(response.get("bundle"));
        Map<String, Object> request = mapValue(response.get("request"));
        Map<String, Object> validation = mapValue(response.get("validation"));
        Map<String, Object> decision = mapValue(response.get("decision"));

        Map<String, Object> details = new LinkedHashMap<>();
        putIfPresent(details, "bundleName", bundle.get("name"));
        putIfPresent(details, "bundleDisplayName", bundle.get("displayName"));
        putIfPresent(details, "bundleOwner", bundle.get("owner"));
        putIfPresent(details, "bundleTenant", bundle.get("tenant"));
        putIfPresent(details, "bundleWorkspace", bundle.get("workspace"));
        putIfPresent(details, "bundleTimezone", bundle.get("timezone"));
        putIfPresent(details, "bundleRuleCount", bundle.get("ruleCount"));
        putIfPresent(details, "evaluatedTool", request.get("tool"));
        putIfPresent(details, "normalizedHost", request.get("normalizedHost"));
        putIfPresent(details, "evaluatedAt", request.get("evaluatedAt"));
        putIfPresent(details, "requestTenant", request.get("tenant"));
        putIfPresent(details, "requestWorkspace", request.get("workspace"));
        putIfPresent(details, "bundleLocalDay", request.get("bundleLocalDay"));
        putIfPresent(details, "bundleLocalTime", request.get("bundleLocalTime"));
        putIfPresent(details, "decisionSource", decision.get("source"));
        putIfPresent(details, "matchedRuleId", decision.get("matchedRuleId"));
        putIfPresent(details, "reason", decision.get("reason"));
        putIfPresent(details, "defaultDecision", decision.get("defaultDecision"));
        putIfPresent(details, "validationValid", validation.get("valid"));

        List<String> validationErrors = stringList(validation.get("errors"));
        if (!validationErrors.isEmpty()) {
            details.put("validationErrors", validationErrors);
        }

        List<Map<String, Object>> trace = mapList(response.get("trace"));
        if (!trace.isEmpty()) {
            details.put("traceSummary", trace);
        }

        observabilityService.recordPolicyDecision(
                stringValue(decision.get("result")),
                details,
                RequestCorrelationHolder.currentCorrelationId()
        );
    }

    private Map<String, Object> bundleSummary(PolicyBundle bundle) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (bundle == null) {
            return summary;
        }
        summary.put("apiVersion", bundle.apiVersion());
        summary.put("kind", bundle.kind());
        summary.put("name", bundle.metadata().name());
        summary.put("displayName", bundle.metadata().displayName());
        summary.put("owner", bundle.metadata().owner());
        summary.put("timezone", bundle.spec().timezoneId());
        summary.put("ruleCount", bundle.spec().rules().size());
        return summary;
    }

    private Map<String, Object> requestSummary(PolicyBundle bundle,
                                               String toolName,
                                               NormalizedTarget normalizedTarget,
                                               Instant evaluationInstant) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tool", toolName);
        request.put("target", normalizedTarget.rawTarget());
        request.put("normalizedHost", normalizedTarget.normalizedHost());
        request.put("evaluatedAt", evaluationInstant.toString());

        if (bundle != null && bundle.spec().timezone() != null) {
            ZonedDateTime bundleTime = evaluationInstant.atZone(bundle.spec().timezone());
            request.put("bundleTimezone", bundle.spec().timezoneId());
            request.put("bundleLocalDay", formatDay(bundleTime.getDayOfWeek()));
            request.put("bundleLocalTime", bundleTime.toLocalTime().withSecond(0).withNano(0).toString());
        }
        return request;
    }

    private String normalizeToolName(String toolName, List<String> errors) {
        if (toolName == null || toolName.isBlank()) {
            errors.add("toolName must be a non-empty exact MCP tool or action");
            return null;
        }

        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        if (!knownActionNames.contains(normalized)) {
            errors.add("toolName must be an exact known MCP tool or action");
        }
        return normalized;
    }

    private NormalizedTarget normalizeTarget(String target, List<String> errors) {
        if (target == null || target.isBlank()) {
            return new NormalizedTarget(null, null);
        }

        String trimmed = target.trim();
        try {
            String host = extractHost(trimmed);
            if (host == null || host.isBlank()) {
                errors.add("target must be a hostname or absolute URL when provided");
                return new NormalizedTarget(trimmed, null);
            }
            return new NormalizedTarget(trimmed, host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return new NormalizedTarget(trimmed, null);
        }
    }

    private Instant parseEvaluationInstant(String evaluatedAt, List<String> errors) {
        if (evaluatedAt == null || evaluatedAt.isBlank()) {
            return Instant.now();
        }

        String trimmed = evaluatedAt.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        errors.add("evaluatedAt must be a valid ISO-8601 instant or offset datetime");
        return Instant.EPOCH;
    }

    private boolean matchesAnyHost(String normalizedHost, List<String> patterns) {
        return patterns.stream().anyMatch(pattern -> hostMatches(normalizedHost, pattern));
    }

    private boolean hostMatches(String normalizedHost, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return normalizedHost.endsWith(suffix) && normalizedHost.length() > suffix.length() - 1;
        }
        return normalizedHost.equals(pattern);
    }

    private boolean matchesAnyWindow(ZonedDateTime bundleTime, List<PolicyTimeWindow> windows) {
        return windows.stream().anyMatch(window -> matchesWindow(bundleTime, window));
    }

    private boolean matchesWindow(ZonedDateTime bundleTime, PolicyTimeWindow window) {
        if (!window.days().contains(bundleTime.getDayOfWeek())) {
            return false;
        }

        LocalTime currentTime = bundleTime.toLocalTime();
        if (window.startTime().isBefore(window.endTime())) {
            return !currentTime.isBefore(window.startTime()) && currentTime.isBefore(window.endTime());
        }
        return !currentTime.isBefore(window.startTime()) || currentTime.isBefore(window.endTime());
    }

    private String extractHost(String target) {
        if (target.contains("://")) {
            try {
                URI uri = URI.create(target);
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException("target must include a valid host when passed as a URL");
                }
                return uri.getHost();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("target must be a valid absolute URL or hostname");
            }
        }

        String candidate = target;
        if (candidate.contains("/") || candidate.contains("?") || candidate.contains("#")) {
            throw new IllegalArgumentException("target must be a hostname or absolute URL when provided");
        }
        if (candidate.contains(":")) {
            candidate = candidate.substring(0, candidate.indexOf(':'));
        }
        if (!isHostPattern(candidate.toLowerCase(Locale.ROOT).replace("*.", ""))) {
            throw new IllegalArgumentException("target must be a hostname or absolute URL when provided");
        }
        return candidate;
    }

    private String requiredText(JsonNode parent, String fieldName, String prefix, List<String> errors) {
        JsonNode value = parent.get(fieldName);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            errors.add(prefix + "." + fieldName + " must be a non-empty string");
            return null;
        }
        return value.asText().trim();
    }

    private String optionalText(JsonNode parent, String fieldName, String prefix, List<String> errors) {
        JsonNode value = parent.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(prefix + "." + fieldName + " must be a non-empty string when present");
            return null;
        }
        return value.asText().trim();
    }

    private LocalTime parseLocalTime(String value, String prefix, List<String> errors) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(prefix + " must use HH:MM 24-hour format");
            return null;
        }
    }

    private void requireExactKeys(JsonNode node, Set<String> allowedKeys, String prefix, List<String> errors) {
        if (node == null || !node.isObject()) {
            return;
        }
        node.fieldNames().forEachRemaining(fieldName -> {
            if (!allowedKeys.contains(fieldName)) {
                errors.add(prefix + " contains unsupported field '" + fieldName + "'");
            }
        });
    }

    private boolean isKebabCase(String value) {
        return value != null && value.matches("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?$");
    }

    private boolean isHostPattern(String value) {
        return value != null && value.matches("^(?:\\*\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*$");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                maps.add((Map<String, Object>) map);
            }
        }
        return List.copyOf(maps);
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String text && !text.isBlank()) {
                strings.add(text);
            }
        }
        return List.copyOf(strings);
    }

    private void putIfPresent(Map<String, Object> details, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (value instanceof String text) {
            if (!text.isBlank()) {
                details.put(key, text);
            }
            return;
        }
        details.put(key, value);
    }

    private String stringValue(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private DayOfWeek parseDayOfWeek(String day) {
        return switch (day) {
            case "mon" -> DayOfWeek.MONDAY;
            case "tue" -> DayOfWeek.TUESDAY;
            case "wed" -> DayOfWeek.WEDNESDAY;
            case "thu" -> DayOfWeek.THURSDAY;
            case "fri" -> DayOfWeek.FRIDAY;
            case "sat" -> DayOfWeek.SATURDAY;
            case "sun" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("Unsupported day: " + day);
        };
    }

    private String formatDay(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "mon";
            case TUESDAY -> "tue";
            case WEDNESDAY -> "wed";
            case THURSDAY -> "thu";
            case FRIDAY -> "fri";
            case SATURDAY -> "sat";
            case SUNDAY -> "sun";
        };
    }

    private record ParseOutcome(PolicyBundle bundle, List<String> errors) {
    }

    private record NormalizedTarget(String rawTarget, String normalizedHost) {
    }

    private record PolicyBundle(String apiVersion,
                                String kind,
                                PolicyBundleMetadata metadata,
                                PolicyBundleSpec spec) {
    }

    private record PolicyBundleMetadata(String name,
                                        String displayName,
                                        String description,
                                        String owner,
                                        Map<String, String> labels) {
    }

    private record PolicyBundleSpec(String defaultDecision,
                                    String evaluationOrder,
                                    String timezoneId,
                                    ZoneId timezone,
                                    List<PolicyRule> rules) {
    }

    private record PolicyRule(String id,
                              String description,
                              String decision,
                              String reason,
                              boolean enabled,
                              PolicyMatch match) {
    }

    private record PolicyMatch(List<String> tools,
                               List<String> hosts,
                               List<PolicyTimeWindow> timeWindows) {
    }

    private record PolicyTimeWindow(List<DayOfWeek> days,
                                    String start,
                                    LocalTime startTime,
                                    String end,
                                    LocalTime endTime) {
    }

    private record RuleEvaluation(PolicyRule rule,
                                  boolean matched,
                                  List<String> matchedSelectors,
                                  List<String> failedSelectors) {
    }
}
