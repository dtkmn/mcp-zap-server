package mcp.server.zap.core.service.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyContext;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Shared Policy Bundle v1 runtime hook for basic tool, host, and time decisions.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BasicPolicyBundleToolExecutionPolicyHook implements ToolExecutionPolicyHook {
    private static final String PROVIDER_NAME = "basic_policy_bundle";
    private static final String SOURCE_INLINE = "inline";
    private static final String SOURCE_FILE = "file";
    private static final String SOURCE_NONE = "none";

    private final PolicyEnforcementProperties policyEnforcementProperties;
    private final PolicyDryRunService policyDryRunService;

    public BasicPolicyBundleToolExecutionPolicyHook(PolicyEnforcementProperties policyEnforcementProperties,
                                                    PolicyDryRunService policyDryRunService) {
        this.policyEnforcementProperties = policyEnforcementProperties;
        this.policyDryRunService = policyDryRunService;
    }

    @Override
    public PolicyEnforcementDecision evaluate(ToolExecutionPolicyContext context) {
        PolicyBundleInput input = resolvePolicyBundle();
        if (!input.configured()) {
            return PolicyEnforcementDecision.abstain(
                    "no_policy_bundle_configured",
                    input.details()
            );
        }
        if (input.errorReason() != null) {
            return PolicyEnforcementDecision.deny(input.errorReason(), input.details());
        }

        Map<String, Object> response = policyDryRunService.preview(
                input.policyBundle(),
                context.toolName(),
                context.target(),
                null
        );
        return decisionFromPreview(response, input.source(), input.details());
    }

    private PolicyBundleInput resolvePolicyBundle() {
        if (hasText(policyEnforcementProperties.getBundle())) {
            return PolicyBundleInput.configured(
                    policyEnforcementProperties.getBundle().trim(),
                    SOURCE_INLINE,
                    baseDetails(SOURCE_INLINE)
            );
        }

        if (!hasText(policyEnforcementProperties.getBundleFile())) {
            return PolicyBundleInput.notConfigured(baseDetails(SOURCE_NONE));
        }

        Map<String, Object> details = baseDetails(SOURCE_FILE);
        details.put("policyBundleFileConfigured", true);
        try {
            Path bundleFile = Path.of(policyEnforcementProperties.getBundleFile().trim());
            if (!Files.isRegularFile(bundleFile)) {
                details.put("policyError", "bundle_file_not_found");
                return PolicyBundleInput.error("policy_bundle_unavailable", details);
            }
            return PolicyBundleInput.configured(Files.readString(bundleFile), SOURCE_FILE, details);
        } catch (IOException | InvalidPathException | SecurityException e) {
            details.put("policyError", "bundle_file_unreadable");
            details.put("errorClass", e.getClass().getSimpleName());
            return PolicyBundleInput.error("policy_bundle_unavailable", details);
        }
    }

    private PolicyEnforcementDecision decisionFromPreview(Map<String, Object> response,
                                                          String source,
                                                          Map<String, Object> sourceDetails) {
        Map<String, Object> validation = mapValue(response.get("validation"));
        Map<String, Object> decision = mapValue(response.get("decision"));
        Map<String, Object> bundle = mapValue(response.get("bundle"));
        Map<String, Object> request = mapValue(response.get("request"));

        Map<String, Object> details = baseDetails(source);
        if (sourceDetails != null && !sourceDetails.isEmpty()) {
            details.putAll(sourceDetails);
        }
        putIfPresent(details, "policyName", bundle.get("name"));
        putIfPresent(details, "bundleName", bundle.get("name"));
        putIfPresent(details, "bundleDisplayName", bundle.get("displayName"));
        putIfPresent(details, "bundleOwner", bundle.get("owner"));
        putIfPresent(details, "bundleTimezone", bundle.get("timezone"));
        putIfPresent(details, "normalizedHost", request.get("normalizedHost"));
        putIfPresent(details, "evaluatedAt", request.get("evaluatedAt"));
        putIfPresent(details, "decisionResult", decision.get("result"));
        putIfPresent(details, "decisionSource", decision.get("source"));
        putIfPresent(details, "matchedRuleId", decision.get("matchedRuleId"));
        putIfPresent(details, "defaultDecision", decision.get("defaultDecision"));
        putIfPresent(details, "validationValid", validation.get("valid"));

        List<String> validationErrors = stringList(validation.get("errors"));
        if (!validationErrors.isEmpty()) {
            details.put("validationErrors", validationErrors);
        }

        String result = stringValue(decision.get("result"));
        String reason = stringValue(decision.get("reason"), "policy_decision_missing_reason");
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            details.put("policyError", "request_or_bundle_invalid");
            return PolicyEnforcementDecision.deny(reason, details);
        }
        if ("allow".equals(result)) {
            return PolicyEnforcementDecision.allow(reason, details);
        }
        if ("deny".equals(result)) {
            return PolicyEnforcementDecision.deny(reason, details);
        }

        details.put("policyError", "decision_result_invalid");
        return PolicyEnforcementDecision.deny("policy_decision_invalid", details);
    }

    private Map<String, Object> baseDetails(String source) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyProvider", PROVIDER_NAME);
        details.put("policySource", source);
        return details;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
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
        if (value instanceof String text) {
            if (!text.isBlank()) {
                details.put(key, text);
            }
            return;
        }
        if (value != null) {
            details.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return stringValue(value, null);
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return fallback;
    }

    private record PolicyBundleInput(boolean configured,
                                     String policyBundle,
                                     String source,
                                     String errorReason,
                                     Map<String, Object> details) {
        private static PolicyBundleInput configured(String policyBundle,
                                                    String source,
                                                    Map<String, Object> details) {
            return new PolicyBundleInput(true, policyBundle, source, null, Map.copyOf(details));
        }

        private static PolicyBundleInput notConfigured(Map<String, Object> details) {
            return new PolicyBundleInput(false, null, SOURCE_NONE, null, Map.copyOf(details));
        }

        private static PolicyBundleInput error(String errorReason, Map<String, Object> details) {
            return new PolicyBundleInput(true, null, SOURCE_FILE, errorReason, Map.copyOf(details));
        }
    }
}
