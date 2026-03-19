package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for queued scan lifecycle tools.
 */
@Service
public class ExpertQueueMcpToolsService implements ExpertToolGroup {
    private final ScanJobQueueService scanJobQueueService;

    public ExpertQueueMcpToolsService(ScanJobQueueService scanJobQueueService) {
        this.scanJobQueueService = scanJobQueueService;
    }

    @Tool(
            name = "zap_queue_active_scan",
            description = "Queue an active scan job with lifecycle tracking, concurrency guardrails, and optional idempotent admission"
    )
    public String queueActiveScan(
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(description = "Scan policy name (optional)") String policy,
            @ToolParam(description = "Optional client-generated idempotency key for safe retry/deduplication") String idempotencyKey
    ) {
        return scanJobQueueService.queueActiveScan(targetUrl, recurse, policy, idempotencyKey);
    }

    @Tool(
            name = "zap_queue_active_scan_as_user",
            description = "Queue an authenticated active scan job with lifecycle tracking, concurrency guardrails, and optional idempotent admission"
    )
    public String queueActiveScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(description = "Scan policy name (optional)") String policy,
            @ToolParam(description = "Optional client-generated idempotency key for safe retry/deduplication") String idempotencyKey
    ) {
        return scanJobQueueService.queueActiveScanAsUser(contextId, userId, targetUrl, recurse, policy, idempotencyKey);
    }

    @Tool(
            name = "zap_queue_spider_scan",
            description = "Queue a spider scan job with lifecycle tracking, concurrency guardrails, and optional idempotent admission"
    )
    public String queueSpiderScan(
            @ToolParam(description = "Target URL to spider") String targetUrl,
            @ToolParam(description = "Optional client-generated idempotency key for safe retry/deduplication") String idempotencyKey
    ) {
        return scanJobQueueService.queueSpiderScan(targetUrl, idempotencyKey);
    }

    @Tool(
            name = "zap_queue_ajax_spider",
            description = "Queue an AJAX Spider scan under the shared job lifecycle with HA-safe dispatch and optional idempotent admission"
    )
    public String queueAjaxSpiderScan(
            @ToolParam(description = "Target URL to crawl with AJAX Spider") String targetUrl,
            @ToolParam(description = "Optional client-generated idempotency key for safe retry/deduplication") String idempotencyKey
    ) {
        return scanJobQueueService.queueAjaxSpiderScan(targetUrl, idempotencyKey);
    }

    @Tool(
            name = "zap_queue_spider_scan_as_user",
            description = "Queue an authenticated spider scan job with lifecycle tracking, concurrency guardrails, and optional idempotent admission"
    )
    public String queueSpiderScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to spider") String targetUrl,
            @ToolParam(description = "Maximum children to crawl (optional)") String maxChildren,
            @ToolParam(description = "Recurse into sub-paths? true/false (optional, default: true)") String recurse,
            @ToolParam(description = "Restrict to subtree only? true/false (optional, default: false)") String subtreeOnly,
            @ToolParam(description = "Optional client-generated idempotency key for safe retry/deduplication") String idempotencyKey
    ) {
        return scanJobQueueService.queueSpiderScanAsUser(
                contextId, userId, targetUrl, maxChildren, recurse, subtreeOnly, idempotencyKey);
    }

    @Tool(
            name = "zap_scan_job_status",
            description = "Get status details for a queued/running/completed scan job"
    )
    public String getScanJobStatus(@ToolParam(description = "Scan job ID") String jobId) {
        return scanJobQueueService.getScanJobStatus(jobId);
    }

    @Tool(
            name = "zap_scan_job_list",
            description = "List scan jobs and current queue state"
    )
    public String listScanJobs(
            @ToolParam(description = "Optional status filter: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED") String statusFilter
    ) {
        return scanJobQueueService.listScanJobs(statusFilter);
    }

    @Tool(
            name = "zap_scan_job_cancel",
            description = "Cancel a queued or running scan job"
    )
    public String cancelScanJob(@ToolParam(description = "Scan job ID") String jobId) {
        return scanJobQueueService.cancelScanJob(jobId);
    }

    @Tool(
            name = "zap_scan_job_retry",
            description = "Retry a failed or cancelled scan job"
    )
    public String retryScanJob(@ToolParam(description = "Scan job ID") String jobId) {
        return scanJobQueueService.retryScanJob(jobId);
    }

    @Tool(
            name = "zap_scan_job_dead_letter_list",
            description = "List dead-letter scan jobs (failed after exhausting retry budget)"
    )
    public String listDeadLetterJobs() {
        return scanJobQueueService.listDeadLetterJobs();
    }

    @Tool(
            name = "zap_scan_job_dead_letter_requeue",
            description = "Requeue a dead-letter scan job as a fresh job with a new retry budget"
    )
    public String requeueDeadLetterJob(
            @ToolParam(description = "Dead-letter scan job ID") String jobId
    ) {
        return scanJobQueueService.requeueDeadLetterJob(jobId);
    }
}
