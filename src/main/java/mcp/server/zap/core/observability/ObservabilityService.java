package mcp.server.zap.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.McpAbuseProtectionDecision;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Centralizes low-cardinality metrics and audit emission for request, auth,
 * authorization, protection, and tool-execution flows.
 */
@Service
public class ObservabilityService {
    private final MeterRegistry meterRegistry;
    private final AuditEventStream auditEventStream;
    private final ClientWorkspaceResolver clientWorkspaceResolver;

    public ObservabilityService(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                AuditEventStream auditEventStream,
                                ClientWorkspaceResolver clientWorkspaceResolver) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.auditEventStream = auditEventStream;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    public void recordHttpRequest(String method,
                                  String path,
                                  int status,
                                  String clientId,
                                  String workspaceId,
                                  Duration duration) {
        Timer.builder("asg.http.requests")
                .description("HTTP request duration for MCP, auth, and actuator flows")
                .tag("method", normalize(method, "unknown"))
                .tag("path", normalizePath(path))
                .tag("status", Integer.toString(status))
                .tag("outcome", normalizeHttpOutcome(status))
                .tag("authenticated", isAuthenticated(clientId) ? "true" : "false")
                .register(meterRegistry)
                .record(duration);
    }

    public void recordAuthentication(String method,
                                     String outcome,
                                     String reason,
                                     String clientId,
                                     String workspaceId,
                                     String correlationId) {
        String normalizedMethod = normalize(method, "unknown");
        String normalizedOutcome = normalize(outcome, "unknown");
        String normalizedReason = normalize(reason, "unknown");
        meterRegistry.counter(
                "asg.auth.events",
                "method", normalizedMethod,
                "outcome", normalizedOutcome,
                "reason", normalizedReason
        ).increment();

        auditEventStream.publish(
                "authentication",
                clientId,
                normalizedOutcome,
                auditDetails(correlationId, clientId, workspaceId, Map.of(
                        "method", normalizedMethod,
                        "reason", normalizedReason
                ))
        );
    }

    public void recordAuthorization(String action,
                                    String outcome,
                                    String reason,
                                    List<String> requiredScopes,
                                    List<String> grantedScopes,
                                    String clientId,
                                    String workspaceId,
                                    String correlationId) {
        String normalizedAction = normalize(action, "unknown");
        String normalizedOutcome = normalize(outcome, "unknown");
        String normalizedReason = normalize(reason, "unknown");
        meterRegistry.counter(
                "asg.authorization.decisions",
                "action", normalizedAction,
                "outcome", normalizedOutcome,
                "reason", normalizedReason
        ).increment();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", normalizedAction);
        if (requiredScopes != null && !requiredScopes.isEmpty()) {
            details.put("requiredScopes", requiredScopes);
        }
        if (grantedScopes != null && !grantedScopes.isEmpty()) {
            details.put("grantedScopes", grantedScopes);
        }

        auditEventStream.publish(
                "authorization",
                clientId,
                normalizedOutcome,
                auditDetails(correlationId, clientId, workspaceId, details)
        );
    }

    public void recordProtectionRejection(McpAbuseProtectionDecision decision, String correlationId) {
        if (decision == null || decision.allowed()) {
            return;
        }

        String toolFamily = classifyToolFamily(decision.toolName());
        meterRegistry.counter(
                "asg.protection.rejections",
                "error", normalize(decision.errorCode(), "unknown"),
                "reason", normalize(decision.reason(), "unknown"),
                "toolFamily", toolFamily
        ).increment();

        auditEventStream.publish(
                "protection_rejection",
                decision.clientId(),
                normalize(decision.errorCode(), "unknown"),
                auditDetails(correlationId, decision.clientId(), decision.workspaceId(), Map.of(
                        "reason", normalize(decision.reason(), "unknown"),
                        "tool", normalize(decision.toolName(), "unknown"),
                        "toolFamily", toolFamily,
                        "retryAfterSeconds", Math.max(1L, decision.retryAfterSeconds())
                ))
        );
    }

    public void recordToolExecution(String toolName,
                                    String outcome,
                                    Duration duration,
                                    String correlationId,
                                    Throwable error) {
        String clientId = clientWorkspaceResolver.resolveCurrentClientId();
        String workspaceId = clientWorkspaceResolver.resolveCurrentWorkspaceId();
        String normalizedTool = normalize(toolName, "unknown");
        String toolFamily = classifyToolFamily(normalizedTool);
        String normalizedOutcome = normalize(outcome, "unknown");

        Timer.builder("asg.tool.executions")
                .description("Tool execution duration grouped by tool and family")
                .tag("tool", normalizedTool)
                .tag("family", toolFamily)
                .tag("outcome", normalizedOutcome)
                .register(meterRegistry)
                .record(duration);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", normalizedTool);
        details.put("toolFamily", toolFamily);
        details.put("durationMs", duration.toMillis());
        if (error != null) {
            details.put("errorClass", error.getClass().getSimpleName());
            if (error.getMessage() != null && !error.getMessage().isBlank()) {
                details.put("errorMessage", truncate(error.getMessage(), 300));
            }
        }

        auditEventStream.publish(
                "tool_execution",
                clientId,
                normalizedOutcome,
                auditDetails(correlationId, clientId, workspaceId, details)
        );
    }

    public void recordPolicyDecision(String outcome,
                                     Map<String, Object> details,
                                     String correlationId) {
        String clientId = clientWorkspaceResolver.resolveCurrentClientId();
        String workspaceId = clientWorkspaceResolver.resolveCurrentWorkspaceId();

        auditEventStream.publish(
                "policy_decision",
                clientId,
                normalize(outcome, "unknown"),
                auditDetails(correlationId, clientId, workspaceId, details == null ? Map.of() : details)
        );
    }

    public String classifyToolFamily(String toolName) {
        String normalizedTool = normalize(toolName, "unknown");
        if (normalizedTool.startsWith("zap_queue_")) {
            return "scan_queue";
        }
        if (normalizedTool.startsWith("zap_scan_history_")) {
            return "scan_history";
        }
        if (normalizedTool.startsWith("zap_automation_")) {
            return "automation";
        }
        if (normalizedTool.startsWith("zap_report_")) {
            return "report";
        }
        if (normalizedTool.startsWith("zap_alert_") || normalizedTool.startsWith("zap_findings_")) {
            return "findings";
        }
        if (normalizedTool.startsWith("zap_import_") || normalizedTool.startsWith("zap_target_import")) {
            return "api_import";
        }
        if (normalizedTool.startsWith("zap_scan_policy_")) {
            return "scan_policy";
        }
        if (normalizedTool.startsWith("zap_policy_")) {
            return "policy_engine";
        }
        if (normalizedTool.startsWith("zap_active_scan_")
                || normalizedTool.startsWith("zap_attack_")
                || normalizedTool.startsWith("zap_crawl_")
                || normalizedTool.startsWith("zap_spider_")
                || normalizedTool.startsWith("zap_ajax_spider")
                || normalizedTool.startsWith("zap_passive_scan_")) {
            return "scan";
        }
        if (normalizedTool.startsWith("zap_context_")
                || normalizedTool.startsWith("zap_user_")
                || normalizedTool.startsWith("zap_auth_")) {
            return "context_auth";
        }
        if (normalizedTool.startsWith("mcp:")) {
            return "mcp";
        }
        return "core";
    }

    private Map<String, Object> auditDetails(String correlationId,
                                             String clientId,
                                             String workspaceId,
                                             Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (correlationId != null && !correlationId.isBlank()) {
            payload.put("correlationId", correlationId);
        }
        payload.put("clientId", normalize(clientId, "anonymous"));
        payload.put("workspaceId", normalize(workspaceId, "default_workspace"));
        payload.putAll(details);
        return payload;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/unknown";
        }
        String normalized = path.trim();
        if (normalized.startsWith("/actuator/metrics/")) {
            return "/actuator/metrics/{name}";
        }
        return normalized;
    }

    private String normalizeHttpOutcome(int status) {
        if (status >= 200 && status < 300) {
            return "success";
        }
        if (status >= 400 && status < 500) {
            return "client_error";
        }
        if (status >= 500) {
            return "server_error";
        }
        return "unknown";
    }

    private boolean isAuthenticated(String clientId) {
        return clientId != null && !clientId.isBlank() && !"anonymous".equalsIgnoreCase(clientId.trim());
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
