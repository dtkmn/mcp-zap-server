package mcp.server.zap.core.service.protection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.protection.McpAbuseProtectionContext;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.protection.McpQuotaLimit;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.service.GuidedExecutionModeResolver;
import mcp.server.zap.core.service.ScanJobQueueService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Evaluates MCP request throttling, workspace quotas, and overload shedding.
 */
@Service
public class McpAbuseProtectionService {
    private final AbuseProtectionProperties properties;
    private final ClientRateLimiter clientRateLimiter;
    private final ClientWorkspaceResolver clientWorkspaceResolver;
    private final OperationRegistry operationRegistry;
    private final ObjectProvider<ScanJobQueueService> scanJobQueueServiceProvider;
    private final GuidedExecutionModeResolver guidedExecutionModeResolver;
    private final ToolScopeRegistry toolScopeRegistry;
    private final ProtectionMetrics metrics;

    public McpAbuseProtectionService(AbuseProtectionProperties properties,
                                     ClientRateLimiter clientRateLimiter,
                                     ClientWorkspaceResolver clientWorkspaceResolver,
                                     OperationRegistry operationRegistry,
                                     ObjectProvider<ScanJobQueueService> scanJobQueueServiceProvider,
                                     GuidedExecutionModeResolver guidedExecutionModeResolver,
                                     ToolScopeRegistry toolScopeRegistry,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        this.clientRateLimiter = clientRateLimiter;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
        this.operationRegistry = operationRegistry;
        this.scanJobQueueServiceProvider = scanJobQueueServiceProvider;
        this.guidedExecutionModeResolver = guidedExecutionModeResolver;
        this.toolScopeRegistry = toolScopeRegistry;
        this.metrics = ProtectionMetrics.create(meterRegistryProvider.getIfAvailable());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public McpAbuseProtectionDecision evaluate(Authentication authentication, String method, String toolName) {
        GatewayToolExecutionContext context = clientWorkspaceResolver.resolveToolExecutionContext(
                authentication,
                null,
                McpToolInvocation.fromJsonRpc(method, toolName),
                null
        );
        return evaluate(context);
    }

    public McpAbuseProtectionDecision evaluate(GatewayToolExecutionContext toolContext) {
        GatewayToolExecutionContext normalizedContext =
                toolContext == null ? GatewayToolExecutionContext.of(null, null, null) : toolContext;
        McpAbuseProtectionContext protectionContext = McpAbuseProtectionContext.from(normalizedContext);
        String clientId = normalizedContext.principalId();
        String workspaceId = normalizedContext.workspaceId();
        String normalizedMethod = normalize(normalizedContext.method());
        String normalizedToolName = normalize(normalizedContext.toolName());

        if (!properties.isEnabled()) {
            return McpAbuseProtectionDecision.allow(protectionContext);
        }

        if (!clientRateLimiter.tryConsume(clientId)) {
            metrics.rateLimited.increment();
            return McpAbuseProtectionDecision.reject(
                    "rate_limited",
                    "client_request_rate",
                    protectionContext,
                    clientRateLimiter.retryAfterSeconds(clientId)
            );
        }

        if (!McpToolInvocation.METHOD_TOOLS_CALL.equals(normalizedMethod) || normalizedToolName == null) {
            return McpAbuseProtectionDecision.allow(protectionContext);
        }

        QueueSnapshot queueSnapshot = snapshotQueue();

        if (toolScopeRegistry.hasCapability(normalizedToolName, ToolScopeRegistry.GUIDED_SCAN_CAPABILITY)) {
            if (guidedExecutionModeResolver.preferQueue()) {
                McpAbuseProtectionDecision queueDecision =
                        evaluateQueueAdmissionLimits(queueSnapshot, protectionContext);
                if (queueDecision != null) {
                    return queueDecision;
                }
            } else {
                McpAbuseProtectionDecision directDecision =
                        evaluateDirectScanLimits(protectionContext);
                if (directDecision != null) {
                    return directDecision;
                }
            }
        }

        if (toolScopeRegistry.hasCapability(normalizedToolName, ToolScopeRegistry.QUEUE_ADMISSION_CAPABILITY)) {
            McpAbuseProtectionDecision queueDecision =
                    evaluateQueueAdmissionLimits(queueSnapshot, protectionContext);
            if (queueDecision != null) {
                return queueDecision;
            }
        }

        if (toolScopeRegistry.hasCapability(normalizedToolName, ToolScopeRegistry.DIRECT_SCAN_CAPABILITY)) {
            McpAbuseProtectionDecision directDecision =
                    evaluateDirectScanLimits(protectionContext);
            if (directDecision != null) {
                return directDecision;
            }
        }

        if (toolScopeRegistry.hasCapability(normalizedToolName, ToolScopeRegistry.AUTOMATION_EXECUTION_CAPABILITY)) {
            if (properties.getWorkspaceQuota().isEnabled()) {
                McpAbuseProtectionDecision workspaceDecision = evaluateQuotaLimit(
                        protectionContext,
                        metrics.workspaceQuotaRejected,
                        "workspace_quota_exceeded",
                        "workspace_automation_plans",
                        operationRegistry.countAutomationPlans(workspaceId),
                        properties.getWorkspaceQuota().getMaxAutomationPlans()
                );
                if (workspaceDecision != null) {
                    return workspaceDecision;
                }
            }
            if (properties.getBackpressure().isEnabled()) {
                McpAbuseProtectionDecision backpressureDecision = evaluateQuotaLimit(
                        protectionContext,
                        metrics.backpressureRejected,
                        "overloaded",
                        "automation_capacity",
                        operationRegistry.countAutomationPlans(),
                        properties.getBackpressure().getMaxAutomationPlans()
                );
                if (backpressureDecision != null) {
                    return backpressureDecision;
                }
            }
        }

        return McpAbuseProtectionDecision.allow(protectionContext);
    }

    private McpAbuseProtectionDecision evaluateQueueAdmissionLimits(QueueSnapshot queueSnapshot,
                                                                    McpAbuseProtectionContext context) {
        if (properties.getWorkspaceQuota().isEnabled()) {
            McpAbuseProtectionDecision workspaceDecision = evaluateQuotaLimit(
                    context,
                    metrics.workspaceQuotaRejected,
                    "workspace_quota_exceeded",
                    "workspace_scan_jobs",
                    queueSnapshot.nonTerminalJobsForWorkspace(context.workspaceId(), clientWorkspaceResolver),
                    properties.getWorkspaceQuota().getMaxQueuedOrRunningScanJobs()
            );
            if (workspaceDecision != null) {
                return workspaceDecision;
            }
        }
        if (properties.getBackpressure().isEnabled()) {
            McpAbuseProtectionDecision backlogDecision = evaluateQuotaLimit(
                    context,
                    metrics.backpressureRejected,
                    "overloaded",
                    "scan_job_backlog",
                    queueSnapshot.nonTerminalJobs(),
                    properties.getBackpressure().getMaxTrackedScanJobs()
            );
            if (backlogDecision != null) {
                return backlogDecision;
            }
            McpAbuseProtectionDecision runningDecision = evaluateQuotaLimit(
                    context,
                    metrics.backpressureRejected,
                    "overloaded",
                    "running_scan_capacity",
                    queueSnapshot.runningJobs(),
                    properties.getBackpressure().getMaxRunningScanJobs()
            );
            if (runningDecision != null) {
                return runningDecision;
            }
        }
        return null;
    }

    private McpAbuseProtectionDecision evaluateDirectScanLimits(McpAbuseProtectionContext context) {
        if (properties.getWorkspaceQuota().isEnabled()) {
            McpAbuseProtectionDecision workspaceDecision = evaluateQuotaLimit(
                    context,
                    metrics.workspaceQuotaRejected,
                    "workspace_quota_exceeded",
                    "workspace_direct_scans",
                    operationRegistry.countDirectScans(context.workspaceId()),
                    properties.getWorkspaceQuota().getMaxDirectScans()
            );
            if (workspaceDecision != null) {
                return workspaceDecision;
            }
        }
        if (properties.getBackpressure().isEnabled()) {
            McpAbuseProtectionDecision backpressureDecision = evaluateQuotaLimit(
                    context,
                    metrics.backpressureRejected,
                    "overloaded",
                    "direct_scan_capacity",
                    operationRegistry.countDirectScans(),
                    properties.getBackpressure().getMaxDirectScans()
            );
            if (backpressureDecision != null) {
                return backpressureDecision;
            }
        }
        return null;
    }

    private McpAbuseProtectionDecision evaluateQuotaLimit(McpAbuseProtectionContext context,
                                                          Counter rejectionCounter,
                                                          String errorCode,
                                                          String reason,
                                                          int currentCount,
                                                          int maxAllowed) {
        McpAbuseProtectionDecision decision = McpQuotaLimit.of(
                errorCode,
                reason,
                currentCount,
                maxAllowed,
                properties.getRetryAfterSeconds()
        ).evaluate(context);
        if (!decision.allowed()) {
            rejectionCounter.increment();
            return decision;
        }
        return null;
    }

    private QueueSnapshot snapshotQueue() {
        ScanJobQueueService queueService = scanJobQueueServiceProvider.getIfAvailable();
        List<ScanJob> jobs = queueService != null ? queueService.listJobsSnapshot() : List.of();
        int nonTerminalJobs = 0;
        int runningJobs = 0;

        for (ScanJob job : jobs) {
            if (job == null || job.getStatus() == null || job.getStatus().isTerminal()) {
                continue;
            }
            nonTerminalJobs++;
            if (job.getStatus() == ScanJobStatus.RUNNING) {
                runningJobs++;
            }
        }

        return new QueueSnapshot(jobs, nonTerminalJobs, runningJobs);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record QueueSnapshot(List<ScanJob> jobs, int nonTerminalJobs, int runningJobs) {
        private int nonTerminalJobsForWorkspace(String workspaceId, ClientWorkspaceResolver resolver) {
            if (workspaceId == null) {
                return 0;
            }
            int count = 0;
            for (ScanJob job : jobs) {
                if (job == null || job.getStatus() == null || job.getStatus().isTerminal()) {
                    continue;
                }
                String requesterWorkspace = resolver.resolveWorkspaceId(job.getRequesterId());
                if (workspaceId.equals(requesterWorkspace)) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class ProtectionMetrics {
        private final Counter rateLimited;
        private final Counter workspaceQuotaRejected;
        private final Counter backpressureRejected;

        private ProtectionMetrics(Counter rateLimited, Counter workspaceQuotaRejected, Counter backpressureRejected) {
            this.rateLimited = rateLimited;
            this.workspaceQuotaRejected = workspaceQuotaRejected;
            this.backpressureRejected = backpressureRejected;
        }

        private static ProtectionMetrics create(MeterRegistry meterRegistry) {
            if (meterRegistry == null) {
                SimpleMeterRegistry noopRegistry = new SimpleMeterRegistry();
                return new ProtectionMetrics(
                        Counter.builder("noop.rate_limited").register(noopRegistry),
                        Counter.builder("noop.workspace_quota").register(noopRegistry),
                        Counter.builder("noop.backpressure").register(noopRegistry)
                );
            }
            return new ProtectionMetrics(
                    Counter.builder("mcp.protection.rate_limited").register(meterRegistry),
                    Counter.builder("mcp.protection.workspace_quota_rejections").register(meterRegistry),
                    Counter.builder("mcp.protection.backpressure_rejections").register(meterRegistry)
            );
        }
    }
}
