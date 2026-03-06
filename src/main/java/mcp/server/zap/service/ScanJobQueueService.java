package mcp.server.zap.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.model.ScanJob;
import mcp.server.zap.model.ScanJobStatus;
import mcp.server.zap.model.ScanJobType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ScanJobQueueService {

    private static final String PARAM_TARGET_URL = "targetUrl";
    private static final String PARAM_RECURSE = "recurse";
    private static final String PARAM_POLICY = "policy";
    private static final String PARAM_CONTEXT_ID = "contextId";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_MAX_CHILDREN = "maxChildren";
    private static final String PARAM_SUBTREE_ONLY = "subtreeOnly";

    private static final AtomicInteger PLATFORM_THREAD_COUNTER = new AtomicInteger(0);

    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private final RetryPolicy activeRetryPolicy;
    private final RetryPolicy spiderRetryPolicy;

    private final ExecutorService ioExecutor;

    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final Deque<String> queuedJobIds = new ArrayDeque<>();
    private final Set<String> pollingJobIds = new HashSet<>();
    private final Set<String> startingJobIds = new HashSet<>();
    private final ReentrantLock queueLock = new ReentrantLock();

    @Autowired
    public ScanJobQueueService(ActiveScanService activeScanService,
                               SpiderScanService spiderScanService,
                               UrlValidationService urlValidationService,
                               ScanLimitProperties scanLimitProperties,
                               @Value("${zap.scan.queue.virtual-threads.enabled:false}") boolean virtualThreadsEnabled,
                               @Value("${zap.scan.queue.retry.active.max-attempts:3}") int activeMaxAttempts,
                               @Value("${zap.scan.queue.retry.active.initial-backoff-ms:2000}") long activeInitialBackoffMs,
                               @Value("${zap.scan.queue.retry.active.max-backoff-ms:30000}") long activeMaxBackoffMs,
                               @Value("${zap.scan.queue.retry.active.multiplier:2.0}") double activeBackoffMultiplier,
                               @Value("${zap.scan.queue.retry.spider.max-attempts:2}") int spiderMaxAttempts,
                               @Value("${zap.scan.queue.retry.spider.initial-backoff-ms:1000}") long spiderInitialBackoffMs,
                               @Value("${zap.scan.queue.retry.spider.max-backoff-ms:10000}") long spiderMaxBackoffMs,
                               @Value("${zap.scan.queue.retry.spider.multiplier:2.0}") double spiderBackoffMultiplier) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                new RetryPolicy(activeMaxAttempts, activeInitialBackoffMs, activeMaxBackoffMs, activeBackoffMultiplier),
                new RetryPolicy(spiderMaxAttempts, spiderInitialBackoffMs, spiderMaxBackoffMs, spiderBackoffMultiplier),
                virtualThreadsEnabled
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                new RetryPolicy(maxAttempts, 0, 0, 1.0),
                new RetryPolicy(maxAttempts, 0, 0, 1.0),
                virtualThreadsEnabled
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled) {
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
        this.activeRetryPolicy = activeRetryPolicy.sanitized();
        this.spiderRetryPolicy = spiderRetryPolicy.sanitized();

        if (virtualThreadsEnabled) {
            this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
            log.info("Scan queue IO executor initialized with virtual threads");
        } else {
            this.ioExecutor = Executors.newCachedThreadPool(task -> {
                Thread t = new Thread(task);
                t.setDaemon(true);
                t.setName("scan-queue-io-" + PLATFORM_THREAD_COUNTER.incrementAndGet());
                return t;
            });
            log.info("Scan queue IO executor initialized with cached platform thread pool");
        }

        log.info("Scan queue retry policy active={} spider={}", this.activeRetryPolicy, this.spiderRetryPolicy);
    }

    @PreDestroy
    void shutdownExecutor() {
        ioExecutor.shutdownNow();
    }

    @Tool(
            name = "zap_queue_active_scan",
            description = "Queue an active scan job with lifecycle tracking and concurrency guardrails"
    )
    public String queueActiveScan(
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(description = "Scan policy name (optional)") String policy
    ) {
        String normalizedTarget = requireText(targetUrl, PARAM_TARGET_URL);
        urlValidationService.validateUrl(normalizedTarget);

        ScanJob job = enqueueJob(ScanJobType.ACTIVE_SCAN, Map.of(
                PARAM_TARGET_URL, normalizedTarget,
                PARAM_RECURSE, hasText(recurse) ? recurse.trim() : "true",
                PARAM_POLICY, hasText(policy) ? policy.trim() : ""
        ));

        return formatSubmission(job);
    }

    @Tool(
            name = "zap_queue_active_scan_as_user",
            description = "Queue an authenticated active scan job with lifecycle tracking and concurrency guardrails"
    )
    public String queueActiveScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (optional, default: true)") String recurse,
            @ToolParam(description = "Scan policy name (optional)") String policy
    ) {
        String normalizedContext = requireText(contextId, PARAM_CONTEXT_ID);
        String normalizedUser = requireText(userId, PARAM_USER_ID);
        String normalizedTarget = requireText(targetUrl, PARAM_TARGET_URL);
        urlValidationService.validateUrl(normalizedTarget);

        ScanJob job = enqueueJob(ScanJobType.ACTIVE_SCAN_AS_USER, Map.of(
                PARAM_CONTEXT_ID, normalizedContext,
                PARAM_USER_ID, normalizedUser,
                PARAM_TARGET_URL, normalizedTarget,
                PARAM_RECURSE, hasText(recurse) ? recurse.trim() : "true",
                PARAM_POLICY, hasText(policy) ? policy.trim() : ""
        ));

        return formatSubmission(job);
    }

    @Tool(
            name = "zap_queue_spider_scan",
            description = "Queue a spider scan job with lifecycle tracking and concurrency guardrails"
    )
    public String queueSpiderScan(
            @ToolParam(description = "Target URL to spider") String targetUrl
    ) {
        String normalizedTarget = requireText(targetUrl, PARAM_TARGET_URL);
        urlValidationService.validateUrl(normalizedTarget);

        ScanJob job = enqueueJob(ScanJobType.SPIDER_SCAN, Map.of(
                PARAM_TARGET_URL, normalizedTarget
        ));

        return formatSubmission(job);
    }

    @Tool(
            name = "zap_queue_spider_scan_as_user",
            description = "Queue an authenticated spider scan job with lifecycle tracking and concurrency guardrails"
    )
    public String queueSpiderScanAsUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId,
            @ToolParam(description = "Target URL to spider") String targetUrl,
            @ToolParam(description = "Maximum children to crawl (optional)") String maxChildren,
            @ToolParam(description = "Recurse into sub-paths? true/false (optional, default: true)") String recurse,
            @ToolParam(description = "Restrict to subtree only? true/false (optional, default: false)") String subtreeOnly
    ) {
        String normalizedContext = requireText(contextId, PARAM_CONTEXT_ID);
        String normalizedUser = requireText(userId, PARAM_USER_ID);
        String normalizedTarget = requireText(targetUrl, PARAM_TARGET_URL);
        urlValidationService.validateUrl(normalizedTarget);

        ScanJob job = enqueueJob(ScanJobType.SPIDER_SCAN_AS_USER, Map.of(
                PARAM_CONTEXT_ID, normalizedContext,
                PARAM_USER_ID, normalizedUser,
                PARAM_TARGET_URL, normalizedTarget,
                PARAM_MAX_CHILDREN, hasText(maxChildren) ? maxChildren.trim() : "",
                PARAM_RECURSE, hasText(recurse) ? recurse.trim() : "true",
                PARAM_SUBTREE_ONLY, hasText(subtreeOnly) ? subtreeOnly.trim() : "false"
        ));

        return formatSubmission(job);
    }

    @Tool(
            name = "zap_scan_job_status",
            description = "Get status details for a queued/running/completed scan job"
    )
    public String getScanJobStatus(
            @ToolParam(description = "Scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        processQueue();

        queueLock.lock();
        try {
            ScanJob job = jobs.get(normalizedJobId);
            if (job == null) {
                throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
            }
            return formatJobDetail(job, queuePosition(normalizedJobId));
        } finally {
            queueLock.unlock();
        }
    }

    @Tool(
            name = "zap_scan_job_list",
            description = "List scan jobs and current queue state"
    )
    public String listScanJobs(
            @ToolParam(description = "Optional status filter: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED") String statusFilter
    ) {
        ScanJobStatus filter = parseStatusFilter(statusFilter);
        processQueue();

        queueLock.lock();
        try {
            List<ScanJob> snapshot = new ArrayList<>(jobs.values());
            snapshot.sort((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()));

            StringBuilder output = new StringBuilder();
            output.append("Scan job summary")
                    .append('\n')
                    .append("Total jobs: ")
                    .append(snapshot.size())
                    .append('\n')
                    .append("Queue depth: ")
                    .append(queuedJobIds.size())
                    .append('\n');

            if (filter != null) {
                output.append("Filter: ").append(filter).append('\n');
            }

            int visible = 0;
            for (ScanJob job : snapshot) {
                if (filter != null && job.getStatus() != filter) {
                    continue;
                }
                visible += 1;
                output.append("- ")
                        .append(job.getId())
                        .append(" | ")
                        .append(job.getType())
                        .append(" | ")
                        .append(job.getStatus())
                        .append(" | attempts=")
                        .append(job.getAttempts())
                        .append('/')
                        .append(job.getMaxAttempts())
                        .append(" | progress=")
                        .append(job.getLastKnownProgress())
                        .append('%');
                if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
                    output.append(" | retryAt=").append(job.getNextAttemptAt());
                }
                output.append('\n');
            }

            if (visible == 0) {
                output.append("No jobs match current filter.");
            }

            return output.toString();
        } finally {
            queueLock.unlock();
        }
    }

    @Tool(
            name = "zap_scan_job_cancel",
            description = "Cancel a queued or running scan job"
    )
    public String cancelScanJob(
            @ToolParam(description = "Scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        StopRequest stopRequest = null;
        String response;

        queueLock.lock();
        try {
            ScanJob job = jobs.get(normalizedJobId);
            if (job == null) {
                throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
            }

            if (job.getStatus() == ScanJobStatus.QUEUED) {
                queuedJobIds.remove(normalizedJobId);
                job.markCancelled();
                response = "Scan job cancelled: " + normalizedJobId + " (was QUEUED)";
            } else if (job.getStatus() == ScanJobStatus.RUNNING) {
                String scanId = job.getZapScanId();
                if (!hasText(scanId)) {
                    job.markCancelled();
                } else {
                    stopRequest = new StopRequest(job.getType(), scanId);
                }
                response = "Scan job cancelled: " + normalizedJobId + " (was RUNNING)";
            } else {
                return "Scan job " + normalizedJobId + " is already terminal with status " + job.getStatus() + ".";
            }
        } finally {
            queueLock.unlock();
        }

        if (stopRequest != null) {
            executeStopRequest(stopRequest);
            queueLock.lock();
            try {
                ScanJob job = jobs.get(normalizedJobId);
                if (job != null && job.getStatus() == ScanJobStatus.RUNNING) {
                    job.markCancelled();
                }
            } finally {
                queueLock.unlock();
            }
        }

        processQueue();
        return response;
    }

    @Tool(
            name = "zap_scan_job_retry",
            description = "Retry a failed or cancelled scan job"
    )
    public String retryScanJob(
            @ToolParam(description = "Scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");

        queueLock.lock();
        try {
            ScanJob job = jobs.get(normalizedJobId);
            if (job == null) {
                throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
            }

            if (job.getStatus() != ScanJobStatus.FAILED && job.getStatus() != ScanJobStatus.CANCELLED) {
                throw new IllegalStateException("Only FAILED or CANCELLED jobs can be retried. Current status: " + job.getStatus());
            }

            if (job.getAttempts() >= job.getMaxAttempts()) {
                throw new IllegalStateException("Retry budget exhausted for job " + normalizedJobId + " (attempts="
                        + job.getAttempts() + "/" + job.getMaxAttempts() + ")");
            }

            job.markQueuedForRetry(Instant.now(), job.getLastError());
            queuedJobIds.addLast(normalizedJobId);
        } finally {
            queueLock.unlock();
        }

        processQueue();

        queueLock.lock();
        try {
            ScanJob job = jobs.get(normalizedJobId);
            return "Retry queued for job " + normalizedJobId + ". Current status: " + (job != null ? job.getStatus() : "UNKNOWN");
        } finally {
            queueLock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${zap.scan.queue.dispatch-interval-ms:2000}")
    public void processQueue() {
        WorkPlan workPlan = planWork();
        if (workPlan.pollTargets().isEmpty() && workPlan.startTargets().isEmpty()) {
            return;
        }

        List<PollResult> pollResults = executePollTargets(workPlan.pollTargets());
        List<StartResult> startResults = executeStartTargets(workPlan.startTargets());
        List<StopRequest> stopRequests = applyResults(pollResults, startResults);

        for (StopRequest stopRequest : stopRequests) {
            try {
                executeStopRequest(stopRequest);
            } catch (Exception e) {
                log.warn("Failed to stop scan {} for cancelled job cleanup: {}", stopRequest.scanId(), e.getMessage());
            }
        }
    }

    ScanJob getJobForTesting(String jobId) {
        queueLock.lock();
        try {
            return jobs.get(jobId);
        } finally {
            queueLock.unlock();
        }
    }

    void processQueueOnceForTesting() {
        processQueue();
    }

    private ScanJob enqueueJob(ScanJobType type, Map<String, String> parameters) {
        ScanJob job = new ScanJob(
                UUID.randomUUID().toString(),
                type,
                parameters,
                Instant.now(),
                policyFor(type).maxAttempts()
        );

        queueLock.lock();
        try {
            jobs.put(job.getId(), job);
            queuedJobIds.addLast(job.getId());
            log.info("Enqueued scan job {} ({})", job.getId(), job.getType());
        } finally {
            queueLock.unlock();
        }

        processQueue();
        return job;
    }

    private WorkPlan planWork() {
        queueLock.lock();
        try {
            List<PollTarget> pollTargets = new ArrayList<>();
            for (ScanJob job : jobs.values()) {
                if (job.getStatus() != ScanJobStatus.RUNNING) {
                    continue;
                }

                if (!hasText(job.getZapScanId())) {
                    job.markFailed("Missing ZAP scan ID while job is RUNNING");
                    continue;
                }

                if (pollingJobIds.add(job.getId())) {
                    pollTargets.add(new PollTarget(job.getId(), job.getType(), job.getZapScanId()));
                }
            }

            int activeCapacity = Math.max(0,
                    scanLimitProperties.getMaxConcurrentActiveScans()
                            - countRunningJobs(true)
                            - countStartingJobs(true));
            int spiderCapacity = Math.max(0,
                    scanLimitProperties.getMaxConcurrentSpiderScans()
                            - countRunningJobs(false)
                            - countStartingJobs(false));

            List<StartTarget> startTargets = new ArrayList<>();
            int itemsToInspect = queuedJobIds.size();
            Instant now = Instant.now();

            for (int i = 0; i < itemsToInspect; i++) {
                String jobId = queuedJobIds.pollFirst();
                if (jobId == null) {
                    break;
                }

                ScanJob job = jobs.get(jobId);
                if (job == null || job.getStatus() != ScanJobStatus.QUEUED) {
                    continue;
                }

                if (job.getNextAttemptAt() != null && now.isBefore(job.getNextAttemptAt())) {
                    queuedJobIds.addLast(jobId);
                    continue;
                }

                boolean activeFamily = job.getType().isActiveFamily();
                if (activeFamily && activeCapacity <= 0) {
                    queuedJobIds.addLast(jobId);
                    continue;
                }
                if (!activeFamily && spiderCapacity <= 0) {
                    queuedJobIds.addLast(jobId);
                    continue;
                }

                startingJobIds.add(jobId);
                job.incrementAttempts();
                startTargets.add(new StartTarget(jobId, job.getType(), job.getParameters()));

                if (activeFamily) {
                    activeCapacity -= 1;
                } else {
                    spiderCapacity -= 1;
                }
            }

            return new WorkPlan(pollTargets, startTargets);
        } finally {
            queueLock.unlock();
        }
    }

    private List<PollResult> executePollTargets(List<PollTarget> pollTargets) {
        List<Future<PollResult>> futures = new ArrayList<>(pollTargets.size());
        for (PollTarget target : pollTargets) {
            futures.add(ioExecutor.submit(() -> {
                try {
                    int progress = readProgress(target.type(), target.scanId());
                    return PollResult.success(target.jobId(), progress);
                } catch (Exception e) {
                    return PollResult.failure(target.jobId(), "Runtime status check failed: " + e.getMessage());
                }
            }));
        }

        List<PollResult> results = new ArrayList<>(pollTargets.size());
        for (int i = 0; i < futures.size(); i++) {
            PollTarget target = pollTargets.get(i);
            results.add(awaitFuture(futures.get(i), () -> PollResult.failure(
                    target.jobId(),
                    "Runtime status check failed: async execution interrupted"
            )));
        }
        return results;
    }

    private List<StartResult> executeStartTargets(List<StartTarget> startTargets) {
        List<Future<StartResult>> futures = new ArrayList<>(startTargets.size());
        for (StartTarget target : startTargets) {
            futures.add(ioExecutor.submit(() -> {
                try {
                    String scanId = startScan(target.type(), target.parameters());
                    return StartResult.success(target.jobId(), target.type(), scanId);
                } catch (Exception e) {
                    return StartResult.failure(target.jobId(), target.type(), "Startup failed: " + e.getMessage());
                }
            }));
        }

        List<StartResult> results = new ArrayList<>(startTargets.size());
        for (int i = 0; i < futures.size(); i++) {
            StartTarget target = startTargets.get(i);
            results.add(awaitFuture(futures.get(i), () -> StartResult.failure(
                    target.jobId(),
                    target.type(),
                    "Startup failed: async execution interrupted"
            )));
        }
        return results;
    }

    private List<StopRequest> applyResults(List<PollResult> pollResults, List<StartResult> startResults) {
        queueLock.lock();
        try {
            List<StopRequest> stopRequests = new ArrayList<>();

            for (PollResult result : pollResults) {
                pollingJobIds.remove(result.jobId());

                ScanJob job = jobs.get(result.jobId());
                if (job == null || job.getStatus() != ScanJobStatus.RUNNING) {
                    continue;
                }

                if (!result.success()) {
                    if (scheduleRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after polling error", job.getId());
                    } else {
                        log.warn("Marking scan job {} as FAILED during status refresh: {}", job.getId(), result.error());
                    }
                    continue;
                }

                job.updateProgress(result.progress());
                if (result.progress() >= 100) {
                    job.markSucceeded(result.progress());
                    log.info("Scan job {} completed successfully", job.getId());
                }
            }

            for (StartResult result : startResults) {
                startingJobIds.remove(result.jobId());

                ScanJob job = jobs.get(result.jobId());
                if (job == null) {
                    continue;
                }

                if (job.getStatus() == ScanJobStatus.CANCELLED) {
                    if (result.success() && hasText(result.scanId())) {
                        stopRequests.add(new StopRequest(result.type(), result.scanId()));
                    }
                    continue;
                }

                if (job.getStatus() != ScanJobStatus.QUEUED) {
                    continue;
                }

                if (result.success()) {
                    job.markRunning(result.scanId());
                    log.info("Started scan job {} as ZAP scan {}", job.getId(), result.scanId());
                } else {
                    if (scheduleRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after startup error", job.getId());
                    } else {
                        log.error("Failed to start scan job {}: {}", job.getId(), result.error());
                    }
                }
            }

            return stopRequests;
        } finally {
            queueLock.unlock();
        }
    }

    private boolean scheduleRetryIfAllowed(ScanJob job, String reason) {
        RetryPolicy retryPolicy = policyFor(job.getType());
        if (job.getAttempts() >= retryPolicy.maxAttempts()) {
            job.markFailed(reason);
            return false;
        }

        long delayMs = retryPolicy.computeDelayMs(job.getAttempts());
        Instant retryAt = Instant.now().plusMillis(delayMs);
        job.markQueuedForRetry(retryAt, reason);
        queuedJobIds.addLast(job.getId());
        return true;
    }

    private int countRunningJobs(boolean activeFamily) {
        int running = 0;
        for (ScanJob job : jobs.values()) {
            if (job.getStatus() != ScanJobStatus.RUNNING) {
                continue;
            }
            if (activeFamily && job.getType().isActiveFamily()) {
                running += 1;
            }
            if (!activeFamily && job.getType().isSpiderFamily()) {
                running += 1;
            }
        }
        return running;
    }

    private int countStartingJobs(boolean activeFamily) {
        int starting = 0;
        for (String jobId : startingJobIds) {
            ScanJob job = jobs.get(jobId);
            if (job == null) {
                continue;
            }
            if (activeFamily && job.getType().isActiveFamily()) {
                starting += 1;
            }
            if (!activeFamily && job.getType().isSpiderFamily()) {
                starting += 1;
            }
        }
        return starting;
    }

    private String startScan(ScanJobType type, Map<String, String> parameters) {
        return switch (type) {
            case ACTIVE_SCAN -> activeScanService.startActiveScanJob(
                    parameters.get(PARAM_TARGET_URL),
                    parameters.get(PARAM_RECURSE),
                    normalizeBlankToNull(parameters.get(PARAM_POLICY))
            );
            case ACTIVE_SCAN_AS_USER -> activeScanService.startActiveScanAsUserJob(
                    parameters.get(PARAM_CONTEXT_ID),
                    parameters.get(PARAM_USER_ID),
                    parameters.get(PARAM_TARGET_URL),
                    parameters.get(PARAM_RECURSE),
                    normalizeBlankToNull(parameters.get(PARAM_POLICY))
            );
            case SPIDER_SCAN -> spiderScanService.startSpiderScanJob(
                    parameters.get(PARAM_TARGET_URL)
            );
            case SPIDER_SCAN_AS_USER -> spiderScanService.startSpiderScanAsUserJob(
                    parameters.get(PARAM_CONTEXT_ID),
                    parameters.get(PARAM_USER_ID),
                    parameters.get(PARAM_TARGET_URL),
                    normalizeBlankToNull(parameters.get(PARAM_MAX_CHILDREN)),
                    parameters.get(PARAM_RECURSE),
                    parameters.get(PARAM_SUBTREE_ONLY)
            );
        };
    }

    private int readProgress(ScanJobType type, String scanId) {
        return switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.getActiveScanProgressPercent(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.getSpiderScanProgressPercent(scanId);
        };
    }

    private void executeStopRequest(StopRequest stopRequest) {
        switch (stopRequest.type()) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.stopActiveScanJob(stopRequest.scanId());
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.stopSpiderScanJob(stopRequest.scanId());
        }
    }

    private ScanJobStatus parseStatusFilter(String statusFilter) {
        if (!hasText(statusFilter)) {
            return null;
        }
        try {
            return ScanJobStatus.valueOf(statusFilter.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status filter: " + statusFilter +
                    ". Expected one of: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED");
        }
    }

    private int queuePosition(String jobId) {
        int position = 1;
        for (String queuedJobId : queuedJobIds) {
            if (queuedJobId.equals(jobId)) {
                return position;
            }
            position += 1;
        }
        return -1;
    }

    private String formatSubmission(ScanJob job) {
        int position;
        queueLock.lock();
        try {
            position = queuePosition(job.getId());
        } finally {
            queueLock.unlock();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Scan job accepted")
                .append('\n')
                .append("Job ID: ").append(job.getId()).append('\n')
                .append("Type: ").append(job.getType()).append('\n')
                .append("Status: ").append(job.getStatus()).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts());

        if (job.getStatus() == ScanJobStatus.QUEUED && position > 0) {
            sb.append('\n').append("Queue Position: ").append(position);
        }

        if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
            sb.append('\n').append("Retry Not Before: ").append(job.getNextAttemptAt());
        }

        if (hasText(job.getZapScanId())) {
            sb.append('\n').append("ZAP Scan ID: ").append(job.getZapScanId());
        }

        return sb.toString();
    }

    private String formatJobDetail(ScanJob job, int queuePosition) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scan job details")
                .append('\n')
                .append("Job ID: ").append(job.getId()).append('\n')
                .append("Type: ").append(job.getType()).append('\n')
                .append("Status: ").append(job.getStatus()).append('\n')
                .append("Attempts: ").append(job.getAttempts()).append('/').append(job.getMaxAttempts()).append('\n')
                .append("Progress: ").append(job.getLastKnownProgress()).append('%').append('\n')
                .append("Submitted: ").append(job.getCreatedAt());

        if (job.getStartedAt() != null) {
            sb.append('\n').append("Started: ").append(job.getStartedAt());
        }
        if (job.getCompletedAt() != null) {
            sb.append('\n').append("Completed: ").append(job.getCompletedAt());
        }
        if (queuePosition > 0) {
            sb.append('\n').append("Queue Position: ").append(queuePosition);
        }
        if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
            sb.append('\n').append("Retry Not Before: ").append(job.getNextAttemptAt());
        }
        if (hasText(job.getZapScanId())) {
            sb.append('\n').append("ZAP Scan ID: ").append(job.getZapScanId());
        }
        if (hasText(job.getLastError())) {
            sb.append('\n').append("Last Error: ").append(job.getLastError());
        }

        return sb.toString();
    }

    private String normalizeBlankToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private RetryPolicy policyFor(ScanJobType type) {
        if (type.isActiveFamily()) {
            return activeRetryPolicy;
        }
        return spiderRetryPolicy;
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private <T> T awaitFuture(Future<T> future, FallbackSupplier<T> fallbackSupplier) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackSupplier.get();
        } catch (ExecutionException e) {
            return fallbackSupplier.get();
        }
    }

    @FunctionalInterface
    private interface FallbackSupplier<T> {
        T get();
    }

    private record WorkPlan(List<PollTarget> pollTargets, List<StartTarget> startTargets) {
    }

    private record PollTarget(String jobId, ScanJobType type, String scanId) {
    }

    private record StartTarget(String jobId, ScanJobType type, Map<String, String> parameters) {
    }

    private record StopRequest(ScanJobType type, String scanId) {
    }

    private record PollResult(String jobId, boolean success, int progress, String error) {
        static PollResult success(String jobId, int progress) {
            return new PollResult(jobId, true, progress, null);
        }

        static PollResult failure(String jobId, String error) {
            return new PollResult(jobId, false, 0, error);
        }
    }

    private record StartResult(String jobId, ScanJobType type, boolean success, String scanId, String error) {
        static StartResult success(String jobId, ScanJobType type, String scanId) {
            return new StartResult(jobId, type, true, scanId, null);
        }

        static StartResult failure(String jobId, ScanJobType type, String error) {
            return new StartResult(jobId, type, false, null, error);
        }
    }

    static final class RetryPolicy {
        private final int maxAttempts;
        private final long initialBackoffMs;
        private final long maxBackoffMs;
        private final double multiplier;

        RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs, double multiplier) {
            this.maxAttempts = maxAttempts;
            this.initialBackoffMs = initialBackoffMs;
            this.maxBackoffMs = maxBackoffMs;
            this.multiplier = multiplier;
        }

        RetryPolicy sanitized() {
            int sanitizedMaxAttempts = Math.max(1, maxAttempts);
            long sanitizedInitial = Math.max(0, initialBackoffMs);
            long sanitizedMax = Math.max(sanitizedInitial, maxBackoffMs);
            double sanitizedMultiplier = multiplier < 1.0 ? 1.0 : multiplier;
            return new RetryPolicy(sanitizedMaxAttempts, sanitizedInitial, sanitizedMax, sanitizedMultiplier);
        }

        int maxAttempts() {
            return maxAttempts;
        }

        long computeDelayMs(int attemptsCompleted) {
            if (maxBackoffMs == 0 || initialBackoffMs == 0) {
                return 0;
            }
            int exponent = Math.max(0, attemptsCompleted - 1);
            double raw = initialBackoffMs * Math.pow(multiplier, exponent);
            long bounded = Math.min(maxBackoffMs, Math.round(raw));
            return Math.max(0, bounded);
        }

        @Override
        public String toString() {
            return "RetryPolicy{" +
                    "maxAttempts=" + maxAttempts +
                    ", initialBackoffMs=" + initialBackoffMs +
                    ", maxBackoffMs=" + maxBackoffMs +
                    ", multiplier=" + multiplier +
                    '}';
        }
    }
}
