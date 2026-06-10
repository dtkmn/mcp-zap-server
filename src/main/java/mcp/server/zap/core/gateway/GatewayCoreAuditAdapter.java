package mcp.server.zap.core.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.context.GatewayExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Adapts security-pack audit payloads to generic gateway-core audit events.
 */
@Component
public class GatewayCoreAuditAdapter {
    private static final String EXTENSION_DETAILS_KEY = "extensionDetails";
    private static final Set<String> TRUSTED_AUDIT_DETAIL_KEYS = Set.of(
            "correlationId",
            "clientId",
            "workspaceId",
            "outcome"
    );

    public GatewayAuditEvent policyDecision(String clientId,
                                            String workspaceId,
                                            String outcome,
                                            String correlationId,
                                            Map<String, Object> details) {
        return policyDecision(GatewayExecutionContext.of(clientId, workspaceId, correlationId), outcome, details);
    }

    public GatewayAuditEvent policyDecision(GatewayExecutionContext context,
                                            String outcome,
                                            Map<String, Object> details) {
        GatewayExecutionContext normalizedContext =
                context == null ? GatewayExecutionContext.unknown() : context;
        return GatewayAuditEvent.of(
                "policy_decision",
                normalizedContext.principalId(),
                outcome,
                auditDetails(
                        normalizedContext.correlationId(),
                        normalizedContext.principalId(),
                        normalizedContext.workspaceId(),
                        outcome,
                        details == null ? Map.of() : details
                )
        );
    }

    private Map<String, Object> auditDetails(String correlationId,
                                             String clientId,
                                             String workspaceId,
                                             String outcome,
                                             Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> reservedDetails = new LinkedHashMap<>();
        Object existingExtensionDetails = null;
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (TRUSTED_AUDIT_DETAIL_KEYS.contains(key)) {
                reservedDetails.put(key, value);
                continue;
            }
            if (EXTENSION_DETAILS_KEY.equals(key)) {
                existingExtensionDetails = value;
                continue;
            }
            payload.put(key, value);
        }

        addExtensionDetails(payload, existingExtensionDetails, reservedDetails);

        if (correlationId != null && !correlationId.isBlank()) {
            payload.put("correlationId", correlationId);
        }
        payload.put("clientId", normalize(clientId, "anonymous"));
        payload.put("workspaceId", normalize(workspaceId, "default_workspace"));
        payload.put("outcome", normalize(outcome, "unknown"));
        return payload;
    }

    private void addExtensionDetails(Map<String, Object> payload,
                                     Object existingExtensionDetails,
                                     Map<String, Object> reservedDetails) {
        if (existingExtensionDetails == null && reservedDetails.isEmpty()) {
            return;
        }

        Map<String, Object> extensionDetails = new LinkedHashMap<>();
        if (existingExtensionDetails instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    extensionDetails.put(key.toString(), value);
                }
            });
        } else if (existingExtensionDetails != null) {
            extensionDetails.put("value", existingExtensionDetails);
        }
        extensionDetails.putAll(reservedDetails);
        if (!extensionDetails.isEmpty()) {
            payload.put(EXTENSION_DETAILS_KEY, extensionDetails);
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }
}
