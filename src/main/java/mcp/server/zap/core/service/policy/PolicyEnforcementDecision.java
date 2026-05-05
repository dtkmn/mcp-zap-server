package mcp.server.zap.core.service.policy;

import java.util.Map;

/**
 * Normalized allow, deny, or abstain decision from a runtime policy hook.
 */
public record PolicyEnforcementDecision(
        Outcome outcome,
        String reason,
        Map<String, Object> details
) {
    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }

    public boolean denied() {
        return outcome == Outcome.DENY;
    }

    public boolean abstained() {
        return outcome == Outcome.ABSTAIN;
    }

    public static PolicyEnforcementDecision allow(String reason) {
        return allow(reason, Map.of());
    }

    public static PolicyEnforcementDecision allow(String reason, Map<String, Object> details) {
        return new PolicyEnforcementDecision(Outcome.ALLOW, reason, safeDetails(details));
    }

    public static PolicyEnforcementDecision deny(String reason) {
        return deny(reason, Map.of());
    }

    public static PolicyEnforcementDecision deny(String reason, Map<String, Object> details) {
        return new PolicyEnforcementDecision(Outcome.DENY, reason, safeDetails(details));
    }

    public static PolicyEnforcementDecision abstain(String reason) {
        return abstain(reason, Map.of());
    }

    public static PolicyEnforcementDecision abstain(String reason, Map<String, Object> details) {
        return new PolicyEnforcementDecision(Outcome.ABSTAIN, reason, safeDetails(details));
    }

    private static Map<String, Object> safeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        return details.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    public enum Outcome {
        ALLOW,
        DENY,
        ABSTAIN
    }
}
