package mcp.server.zap.core.service.protection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.service.GuidedExecutionModeResolver;
import mcp.server.zap.core.service.ScanJobQueueService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Evaluates MCP request throttling, workspace quotas, and overload shedding.
 */
@Service
public class McpAbuseProtectionService {
    private static final Set<String> QUEUE_ADMISSION_TOOLS = Set.of(
            "zap_queue_active_scan",
            "zap_queue_active_scan_as_user",
            "zap_queue_spider_scan",
            "zap_queue_spider_scan_as_user",
            "zap_queue_ajax_spider",
            "zap_scan_job_retry",
            "zap_scan_job_dead_letter_requeue"
    );
    private static final Set<String> DIRECT_SCAN_TOOLS = Set.of(
            "zap_active_scan_start",
            "zap_active_scan_as_user",
            "zap_spider_start",
            "zap_spider_as_user",
            "zap_ajax_spider"
    );
    private static final Set<String> AUTOMATION_TOOLS = Set.of("zap_automation_plan_run");
    private static final Set<String> GUIDED_SCAN_TOOLS = Set.of("zap_crawl_start", "zap_attack_start");

    private final AbuseProtectionProperties properties;
    private final ClientRateLimiter clientRateLimiter;
    private final ClientWorkspaceResolver clientWorkspaceResolver;
    private final OperationRegistry operationRegistry;
    private final ObjectProvider<ScanJobQueueService> scanJobQueueServiceProvider;
    private final GuidedExecutionModeResolver guidedExecutionModeResolver;
    private final ProtectionMetrics metrics;

    public McpAbuseProtectionService(AbuseProtectionProperties properties,
                                     ClientRateLimiter clientRateLimiter,
                                     ClientWorkspaceResolver clientWorkspaceResolver,
                                     OperationRegistry operationRegistry,
                                     ObjectProvider<ScanJobQueueService> scanJobQueueServiceProvider,
                                     GuidedExecutionModeResolver guidedExecutionModeResolver,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        this.clientRateLimiter = clientRateLimiter;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
        this.operationRegistry = operationRegistry;
        this.scanJobQueueServiceProvider = scanJobQueueServiceProvider;
        this.guidedExecutionModeResolver = guidedExecutionModeResolver;
        this.metrics = ProtectionMetrics.create(meterRegistryProvider.getIfAvailable());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public McpAbuseProtectionDecision evaluate(Authentication authentication, String method, String toolName) {
        if (!properties.isEnabled()) {
            String clientId = clientWorkspaceResolver.resolveClientId(authentication);
            String workspaceId = clientWorkspaceResolver.resolveWorkspaceId(clientId);
            return McpAbuseProtectionDecision.allow(toolName, clientId, workspaceId);
        }

        String clientId = clientWorkspaceResolver.resolveClientId(authentication);
        String workspaceId = clientWorkspaceResolver.resolveWorkspaceId(clientId);
        String normalizedMethod = normalize(method);
        String normalizedToolName = normalize(toolName);

        if (!clientRateLimiter.tryConsume(clientId)) {
            metrics.rateLimited.increment();
            return McpAbuseProtectionDecision.reject(
                    "rate_limited",
                    "client_request_rate",
                    normalizedToolName,
                    clientId,
                    workspaceId,
                    clientRateLimiter.retryAfterSeconds(clientId)
            );
        }

        if (!"tools/call".equals(normalizedMethod) || normalizedToolName == null) {
            return McpAbuseProtectionDecision.allow(normalizedToolName, clientId, workspaceId);
        }

        QueueSnapshot queueSnapshot = snapshotQueue();

        if (GUIDED_SCAN_TOOLS.contains(normalizedToolName)) {
            if (guidedExecutionModeResolver.preferQueue()) {
                McpAbuseProtectionDecision queueDecision =
                        evaluateQueueAdmissionLimits(queueSnapshot, normalizedToolName, clientId, workspaceId);
                if (queueDecision != null) {
                    return queueDecision;
                }
            } else {
                McpAbuseProtectionDecision directDecision =
                        evaluateDirectScanLimits(normalizedToolName, clientId, workspaceId);
                if (directDecision != null) {
                    return directDecision;
                }
            }
        }

        if (QUEUE_ADMISSION_TOOLS.contains(normalizedToolName)) {
            McpAbuseProtectionDecision queueDecision =
                    evaluateQueueAdmissionLimits(queueSnapshot, normalizedToolName, clientId, workspaceId);
            if (queueDecision != null) {
                return queueDecision;
            }
        }

        if (DIRECT_SCAN_TOOLS.contains(normalizedToolName)) {
            McpAbuseProtectionDecision directDecision =
                    evaluateDirectScanLimits(normalizedToolName, clientId, workspaceId);
            if (directDecision != null) {
                return directDecision;
            }
        }

        if (AUTOMATION_TOOLS.contains(normalizedToolName)) {
            if (properties.getWorkspaceQuota().isEnabled()
                    && operationRegistry.countAutomationPlans(workspaceId) >= properties.getWorkspaceQuota().getMaxAutomationPlans()) {
                metrics.workspaceQuotaRejected.increment();
                return McpAbuseProtectionDecision.reject(
                        "workspace_quota_exceeded",
                        "workspace_automation_plans",
                        normalizedToolName,
                        clientId,
                        workspaceId,
                        properties.getRetryAfterSeconds()
                );
            }
            if (properties.getBackpressure().isEnabled()
                    && operationRegistry.countAutomationPlans() >= properties.getBackpressure().getMaxAutomationPlans()) {
                metrics.backpressureRejected.increment();
                return McpAbuseProtectionDecision.reject(
                        "overloaded",
                        "automation_capacity",
                        normalizedToolName,
                        clientId,
                        workspaceId,
                        properties.getRetryAfterSeconds()
                );
            }
        }

        return McpAbuseProtectionDecision.allow(normalizedToolName, clientId, workspaceId);
    }

    private McpAbuseProtectionDecision evaluateQueueAdmissionLimits(QueueSnapshot queueSnapshot,
                                                                    String toolName,
                                                                    String clientId,
                                                                    String workspaceId) {
        if (properties.getWorkspaceQuota().isEnabled()) {
            int workspaceJobs = queueSnapshot.nonTerminalJobsForWorkspace(workspaceId, clientWorkspaceResolver);
            if (workspaceJobs >= properties.getWorkspaceQuota().getMaxQueuedOrRunningScanJobs()) {
                metrics.workspaceQuotaRejected.increment();
                return McpAbuseProtectionDecision.reject(
                        "workspace_quota_exceeded",
                        "workspace_scan_jobs",
                        toolName,
                        clientId,
                        workspaceId,
                        properties.getRetryAfterSeconds()
                );
            }
        }
        if (properties.getBackpressure().isEnabled()) {
            if (queueSnapshot.nonTerminalJobs() >= properties.getBackpressure().getMaxTrackedScanJobs()) {
                metrics.backpressureRejected.increment();
                return McpAbuseProtectionDecision.reject(
                        "overloaded",
                        "scan_job_backlog",
                        toolName,
                        clientId,
                        workspaceId,
                        properties.getRetryAfterSeconds()
                );
            }
            if (queueSnapshot.runningJobs() >= properties.getBackpressure().getMaxRunningScanJobs()) {
                metrics.backpressureRejected.increment();
                return McpAbuseProtectionDecision.reject(
                        "overloaded",
                        "running_scan_capacity",
                        toolName,
                        clientId,
                        workspaceId,
                        properties.getRetryAfterSeconds()
                );
            }
        }
        return null;
    }

    private McpAbuseProtectionDecision evaluateDirectScanLimits(String toolName,
                                                                String clientId,
                                                                String workspaceId) {
        if (properties.getWorkspaceQuota().isEnabled()
                && operationRegistry.countDirectScans(workspaceId) >= properties.getWorkspaceQuota().getMaxDirectScans()) {
            metrics.workspaceQuotaRejected.increment();
            return McpAbuseProtectionDecision.reject(
                    "workspace_quota_exceeded",
                    "workspace_direct_scans",
                    toolName,
                    clientId,
                    workspaceId,
                    properties.getRetryAfterSeconds()
            );
        }
        if (properties.getBackpressure().isEnabled()
                && operationRegistry.countDirectScans() >= properties.getBackpressure().getMaxDirectScans()) {
            metrics.backpressureRejected.increment();
            return McpAbuseProtectionDecision.reject(
                    "overloaded",
                    "direct_scan_capacity",
                    toolName,
                    clientId,
                    workspaceId,
                    properties.getRetryAfterSeconds()
            );
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
