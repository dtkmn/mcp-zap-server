package mcp.server.zap.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mcp.gateway.core.audit.GatewayAuditSink;
import mcp.gateway.core.context.GatewayExecutionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.spring.webflux.McpAdapterRejectionReason;
import mcp.server.zap.core.gateway.GatewayCoreAuditAdapter;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Centralizes low-cardinality metrics and audit emission for request, auth,
 * authorization, protection, and tool-execution flows.
 */
@Service
public class ObservabilityService {
    private static final int MAX_HTTP_REQUEST_SERIES = 1024;
    // Reserve one overflow series for each outcome/authenticated combination.
    private static final int MAX_NORMAL_HTTP_REQUEST_SERIES = MAX_HTTP_REQUEST_SERIES - 8;
    private final MeterRegistry meterRegistry;
    private final Set<HttpRequestMetricTags> httpRequestMetricTags = new HashSet<>();
    private final GatewayAuditSink auditEventSink;
    private final ClientWorkspaceResolver clientWorkspaceResolver;
    private final GatewayCoreAuditAdapter gatewayCoreAuditAdapter;

    public ObservabilityService(ObjectProvider<MeterRegistry> meterRegistryProvider,
                                GatewayAuditSink auditEventSink,
                                ClientWorkspaceResolver clientWorkspaceResolver,
                                GatewayCoreAuditAdapter gatewayCoreAuditAdapter) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        this.auditEventSink = auditEventSink;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
        this.gatewayCoreAuditAdapter = gatewayCoreAuditAdapter;
    }

    public void recordHttpRequest(String method,
                                  String routePattern,
                                  int status,
                                  String clientId,
                                  Duration duration) {
        HttpRequestMetricTags tags = boundedHttpRequestTags(new HttpRequestMetricTags(
                normalizeHttpMethod(method),
                routePattern == null || routePattern.isBlank() ? "/unmatched" : normalizePath(routePattern),
                status >= 100 && status < 600 ? Integer.toString(status) : "unknown",
                normalizeHttpOutcome(status),
                isAuthenticated(clientId) ? "true" : "false"));
        Timer.builder("mcp.zap.http.requests")
                .description("HTTP request duration for MCP, auth, and actuator flows")
                .tag("method", tags.method())
                .tag("path", tags.path())
                .tag("status", tags.status())
                .tag("outcome", tags.outcome())
                .tag("authenticated", tags.authenticated())
                .register(meterRegistry)
                .record(duration);
    }

    private HttpRequestMetricTags boundedHttpRequestTags(HttpRequestMetricTags tags) {
        synchronized (httpRequestMetricTags) {
            if (httpRequestMetricTags.contains(tags)) {
                return tags;
            }
            if (httpRequestMetricTags.size() < MAX_NORMAL_HTTP_REQUEST_SERIES) {
                httpRequestMetricTags.add(tags);
                return tags;
            }
        }
        return new HttpRequestMetricTags("other", "/overflow", "unknown", tags.outcome(), tags.authenticated());
    }

    private String normalizeHttpMethod(String method) {
        if (method == null || method.isBlank()) {
            return "unknown";
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT" ->
                    method.toLowerCase(Locale.ROOT);
            default -> "other";
        };
    }

    private record HttpRequestMetricTags(String method, String path, String status,
                                         String outcome, String authenticated) {
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
                "mcp.zap.auth.events",
                "method", normalizedMethod,
                "outcome", normalizedOutcome,
                "reason", normalizedReason
        ).increment();

        auditEventSink.publish(
                "authentication",
                clientId,
                normalizedOutcome,
                auditDetails(correlationId, clientId, workspaceId, Map.of(
                        "method", normalizedMethod,
                        "reason", normalizedReason
                ))
        );
    }

    public void recordAuthRateLimitRejection(String endpoint, String correlationId) {
        String normalizedEndpoint = normalizeAuthEndpoint(endpoint);
        meterRegistry.counter(
                "mcp.zap.auth.rate_limit.rejections",
                "endpoint", normalizedEndpoint
        ).increment();

        auditEventSink.publish(
                "auth_rate_limit_rejection",
                "anonymous",
                "rate_limited",
                auditDetails(correlationId, "anonymous", "default-workspace", Map.of(
                        "endpoint", normalizedEndpoint
                ))
        );
    }

    public void recordAuthorizationMetrics(String action, String outcome, String reason) {
        String normalizedAction = normalize(action, "unknown");
        String normalizedOutcome = normalize(outcome, "unknown");
        String normalizedReason = normalize(reason, "unknown");
        meterRegistry.counter(
                "mcp.zap.authorization.decisions",
                "action", normalizedAction,
                "outcome", normalizedOutcome,
                "reason", normalizedReason
        ).increment();
    }

    public void recordProtectionRejectionMetrics(McpAbuseProtectionDecision decision) {
        if (decision == null || decision.allowed()) {
            return;
        }

        String toolFamily = classifyToolFamily(decision.toolName());
        meterRegistry.counter(
                "mcp.zap.protection.rejections",
                "error", normalize(decision.errorCode(), "unknown"),
                "reason", normalize(decision.reason(), "unknown"),
                "toolFamily", toolFamily
        ).increment();
    }

    public void recordInvalidMcpRequestMetrics(String reason) {
        String normalizedReason = normalize(reason, "unknown");
        meterRegistry.counter(
                "mcp.zap.invalid_mcp_requests",
                "reason", normalizedReason
        ).increment();
    }

    public void recordAdapterRejectionMetrics(McpAdapterRejectionReason reason) {
        meterRegistry.counter(
                "mcp.zap.adapter.rejections",
                "reason", reason.code()
        ).increment();
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

        Timer.builder("mcp.zap.tool.executions")
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

        auditEventSink.publish(
                "tool_execution",
                clientId,
                normalizedOutcome,
                auditDetails(correlationId, clientId, workspaceId, details)
        );
    }

    public void recordPolicyDecision(String outcome,
                                     Map<String, Object> details,
                                     String correlationId) {
        GatewayExecutionContext context = clientWorkspaceResolver.resolveCurrentExecutionContext(correlationId);

        auditEventSink.publish(gatewayCoreAuditAdapter.policyDecision(
                context,
                normalize(outcome, "unknown"),
                details
        ));
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

    private String normalizeAuthEndpoint(String path) {
        String normalized = normalizePath(path);
        return switch (normalized) {
            case "/auth/token", "/auth/refresh", "/auth/revoke" -> normalized;
            default -> "/auth/unknown";
        };
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
