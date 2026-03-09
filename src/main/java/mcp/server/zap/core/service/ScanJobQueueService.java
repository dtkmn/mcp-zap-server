package mcp.server.zap.core.service;

import jakarta.annotation.PreDestroy;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

@Service
public class ScanJobQueueService {
    private static final Logger log = LoggerFactory.getLogger(ScanJobQueueService.class);

    private static final String PARAM_TARGET_URL = "targetUrl";
    private static final String PARAM_RECURSE = "recurse";
    private static final String PARAM_POLICY = "policy";
    private static final String PARAM_CONTEXT_ID = "contextId";
    private static final String PARAM_USER_ID = "userId";
    private static final String PARAM_MAX_CHILDREN = "maxChildren";
    private static final String PARAM_SUBTREE_ONLY = "subtreeOnly";
    private static final String PARAM_REPLAY_OF_JOB_ID = "replayOfJobId";

    private static final AtomicInteger PLATFORM_THREAD_COUNTER = new AtomicInteger(0);
    private static final Comparator<ScanJob> JOB_CREATION_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private final RetryPolicy activeRetryPolicy;
    private final RetryPolicy spiderRetryPolicy;
    private final ScanJobStore scanJobStore;
    private final QueueLeadershipCoordinator queueLeadershipCoordinator;

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
                               ObjectProvider<ScanJobStore> scanJobStoreProvider,
                               ObjectProvider<QueueLeadershipCoordinator> queueLeadershipCoordinatorProvider,
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
                virtualThreadsEnabled,
                scanJobStoreProvider.getIfAvailable(InMemoryScanJobStore::new),
                queueLeadershipCoordinatorProvider.getIfAvailable(SingleNodeQueueLeadershipCoordinator::new)
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
                maxAttempts,
                virtualThreadsEnabled,
                new InMemoryScanJobStore(),
                new SingleNodeQueueLeadershipCoordinator()
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                maxAttempts,
                virtualThreadsEnabled,
                scanJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore,
                        QueueLeadershipCoordinator queueLeadershipCoordinator) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                new RetryPolicy(maxAttempts, 0, 0, 1.0),
                new RetryPolicy(maxAttempts, 0, 0, 1.0),
                virtualThreadsEnabled,
                scanJobStore,
                queueLeadershipCoordinator
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                activeRetryPolicy,
                spiderRetryPolicy,
                virtualThreadsEnabled,
                new InMemoryScanJobStore(),
                new SingleNodeQueueLeadershipCoordinator()
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore) {
        this(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                activeRetryPolicy,
                spiderRetryPolicy,
                virtualThreadsEnabled,
                scanJobStore,
                new SingleNodeQueueLeadershipCoordinator()
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore,
                        QueueLeadershipCoordinator queueLeadershipCoordinator) {
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
        this.activeRetryPolicy = activeRetryPolicy.sanitized();
        this.spiderRetryPolicy = spiderRetryPolicy.sanitized();
        this.scanJobStore = scanJobStore != null ? scanJobStore : new InMemoryScanJobStore();
        this.queueLeadershipCoordinator = queueLeadershipCoordinator != null
                ? queueLeadershipCoordinator
                : new SingleNodeQueueLeadershipCoordinator();

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
        restoreStateFromStore();
    }

    @PreDestroy
    void shutdownExecutor() {
        ioExecutor.shutdownNow();
        queueLeadershipCoordinator.close();
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

        ScanJob job = scanJobStore.load(normalizedJobId).orElse(null);
        if (job == null) {
            throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
        }
        return formatJobDetail(job, job.getQueuePosition());
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

        List<ScanJob> snapshot = scanJobStore.list().stream()
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList();

        StringBuilder output = new StringBuilder();
        output.append("Scan job summary")
                .append('\n')
                .append("Total jobs: ")
                .append(snapshot.size())
                .append('\n')
                .append("Queue depth: ")
                .append(snapshot.stream().filter(job -> job.getStatus() == ScanJobStatus.QUEUED).count())
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
            if (job.getQueuePosition() > 0) {
                output.append(" | queuePosition=").append(job.getQueuePosition());
            }
            if (isDeadLetterJob(job)) {
                output.append(" | dead-letter=true");
            }
            if (job.getStatus() == ScanJobStatus.QUEUED && job.getNextAttemptAt() != null) {
                output.append(" | retryAt=").append(job.getNextAttemptAt());
            }
            output.append('\n');
        }

        if (visible == 0) {
            output.append("No jobs match current filter.");
        }

        return output.toString();
    }

    @Tool(
            name = "zap_scan_job_cancel",
            description = "Cancel a queued or running scan job"
    )
    public String cancelScanJob(
            @ToolParam(description = "Scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        StopRequest[] stopRequest = new StopRequest[1];
        String[] response = new String[1];

        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob job = currentState.jobs().get(normalizedJobId);
            if (job == null) {
                throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
            }

            if (job.getStatus() == ScanJobStatus.QUEUED) {
                currentState.queuedJobIds().remove(normalizedJobId);
                job.markCancelled();
                response[0] = "Scan job cancelled: " + normalizedJobId + " (was QUEUED)";
                return currentState;
            }

            if (job.getStatus() == ScanJobStatus.RUNNING) {
                if (!hasText(job.getZapScanId())) {
                    job.markCancelled();
                } else {
                    stopRequest[0] = new StopRequest(job.getType(), job.getZapScanId());
                }
                response[0] = "Scan job cancelled: " + normalizedJobId + " (was RUNNING)";
                return currentState;
            }

            response[0] = "Scan job " + normalizedJobId + " is already terminal with status " + job.getStatus() + ".";
            return currentState;
        });
        applyCommittedStoredJobs(committedJobs);

        if (stopRequest[0] != null) {
            executeStopRequest(stopRequest[0]);
            committedJobs = updateQueueState(currentState -> {
                ScanJob job = currentState.jobs().get(normalizedJobId);
                if (job != null
                        && job.getStatus() == ScanJobStatus.RUNNING
                        && stopRequest[0].scanId().equals(job.getZapScanId())) {
                    job.markCancelled();
                }
                return currentState;
            });
            applyCommittedStoredJobs(committedJobs);
        }

        processQueue();
        return response[0];
    }

    @Tool(
            name = "zap_scan_job_retry",
            description = "Retry a failed or cancelled scan job"
    )
    public String retryScanJob(
            @ToolParam(description = "Scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob job = currentState.jobs().get(normalizedJobId);
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

            currentState.queuedJobIds().remove(normalizedJobId);
            job.markQueuedForRetry(Instant.now(), job.getLastError());
            currentState.queuedJobIds().addLast(normalizedJobId);
            return currentState;
        });
        applyCommittedStoredJobs(committedJobs);

        processQueue();

        queueLock.lock();
        try {
            ScanJob job = jobs.get(normalizedJobId);
            return "Retry queued for job " + normalizedJobId + ". Current status: " + (job != null ? job.getStatus() : "UNKNOWN");
        } finally {
            queueLock.unlock();
        }
    }

    @Tool(
            name = "zap_scan_job_dead_letter_list",
            description = "List dead-letter scan jobs (failed after exhausting retry budget)"
    )
    /**
     * List jobs that exhausted retry budget and remain in FAILED state.
     */
    public String listDeadLetterJobs() {
        processQueue();

        List<ScanJob> deadLetterJobs = scanJobStore.list().stream()
                .filter(this::isDeadLetterJob)
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList();

        StringBuilder output = new StringBuilder();
        output.append("Dead-letter jobs: ").append(deadLetterJobs.size());
        if (deadLetterJobs.isEmpty()) {
            return output.toString();
        }

        output.append('\n');
        for (ScanJob job : deadLetterJobs) {
            output.append("- ")
                    .append(job.getId())
                    .append(" | ")
                    .append(job.getType())
                    .append(" | attempts=")
                    .append(job.getAttempts())
                    .append('/')
                    .append(job.getMaxAttempts());
            if (hasText(job.getLastError())) {
                output.append(" | lastError=").append(job.getLastError());
            }
            output.append('\n');
        }
        return output.toString().trim();
    }

    @Tool(
            name = "zap_scan_job_dead_letter_requeue",
            description = "Requeue a dead-letter scan job as a fresh job with a new retry budget"
    )
    public String requeueDeadLetterJob(
            @ToolParam(description = "Dead-letter scan job ID") String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        ScanJob[] replayJob = new ScanJob[1];
        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob deadLetterJob = currentState.jobs().get(normalizedJobId);
            if (deadLetterJob == null) {
                throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
            }
            if (!isDeadLetterJob(deadLetterJob)) {
                throw new IllegalStateException("Job " + normalizedJobId + " is not a dead-letter job. Current status="
                        + deadLetterJob.getStatus() + ", attempts=" + deadLetterJob.getAttempts()
                        + "/" + deadLetterJob.getMaxAttempts());
            }

            Map<String, String> replayParameters = new HashMap<>(deadLetterJob.getParameters());
            replayParameters.put(PARAM_REPLAY_OF_JOB_ID, deadLetterJob.getId());

            replayJob[0] = new ScanJob(
                    UUID.randomUUID().toString(),
                    deadLetterJob.getType(),
                    replayParameters,
                    Instant.now(),
                    policyFor(deadLetterJob.getType()).maxAttempts()
            );

            currentState.jobs().put(replayJob[0].getId(), replayJob[0]);
            currentState.queuedJobIds().addLast(replayJob[0].getId());
            return currentState;
        });
        applyCommittedStoredJobs(committedJobs);
        log.info("Requeued dead-letter scan job {} as {}", normalizedJobId, replayJob[0].getId());

        processQueue();
        return "Dead-letter replay queued\n"
                + "Source Job ID: " + normalizedJobId + '\n'
                + "New Job ID: " + replayJob[0].getId() + '\n'
                + "New Status: " + replayJob[0].getStatus();
    }

    /**
     * Dispatch queued work, refresh running progress, and persist resulting state.
     */
    @Scheduled(fixedDelayString = "${zap.scan.queue.dispatch-interval-ms:2000}")
    public void processQueue() {
        LeadershipDecision leadershipDecision = evaluateLeadership();
        if (!leadershipDecision.leader()) {
            return;
        }

        restoreStateFromStore(false);

        WorkPlan workPlan = planWork();
        if (workPlan.pollTargets().isEmpty() && workPlan.startTargets().isEmpty()) {
            if (workPlan.stateUpdated()) {
                persistState();
            }
            return;
        }

        List<PollResult> pollResults = executePollTargets(workPlan.pollTargets());
        List<StartResult> startResults = executeStartTargets(workPlan.startTargets());
        ApplyOutcome applyOutcome = applyResults(pollResults, startResults);
        if (workPlan.stateUpdated() || applyOutcome.stateUpdated()) {
            persistState();
        }

        for (StopRequest stopRequest : applyOutcome.stopRequests()) {
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

    /**
     * Evaluate leadership and run transition hooks for acquire/loss events.
     */
    private LeadershipDecision evaluateLeadership() {
        LeadershipDecision leadershipDecision = queueLeadershipCoordinator.evaluateLeadership();
        if (leadershipDecision.acquiredLeadership()) {
            log.info("Queue leadership acquired; restoring durable scan job state before dispatch/mutation");
            restoreStateFromStore();
        }
        if (leadershipDecision.lostLeadership()) {
            log.warn("Queue leadership lost; this replica will stop dispatching/mutating scan jobs");
        }
        return leadershipDecision;
    }

    /**
     * Restore in-memory queue state from durable storage with normalization.
     */
    private void restoreStateFromStore() {
        restoreStateFromStore(true);
    }

    private void restoreStateFromStore(boolean verbose) {
        List<ScanJob> persistedJobs = scanJobStore.list();
        if (persistedJobs.isEmpty()) {
            if (verbose) {
                log.info("No durable scan job state found; starting with empty queue state");
            }
            return;
        }

        NormalizedQueueState normalizedState = normalizeStoredJobs(persistedJobs);

        queueLock.lock();
        try {
            applyNormalizedStateLocked(normalizedState, true);
            if (normalizedState.normalized()) {
                syncScanJobStoreLocked();
            }
        } finally {
            queueLock.unlock();
        }

        if (normalizedState.repairedRunningJobs() > 0) {
            log.warn("Repaired {} restored RUNNING jobs missing ZAP scan IDs", normalizedState.repairedRunningJobs());
        }
        if (verbose) {
            log.info(
                    "Restored durable scan job state with {} jobs (queue depth {})",
                    normalizedState.jobs().size(),
                    normalizedState.queuedJobIds().size()
            );
        }
    }

    /**
     * Persist queue state under lock.
     */
    private void persistState() {
        queueLock.lock();
        try {
            persistStateLocked();
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Persist the current queue view into the durable scan job store.
     */
    private void persistStateLocked() {
        List<ScanJob> committedJobs = scanJobStore.updateAndGet(this::mergeCurrentStateIntoStoredJobs);
        applyNormalizedStateLocked(normalizeStoredJobs(committedJobs), false);
    }

    /**
     * Create a new queued job and trigger dispatch loop.
     */
    private ScanJob enqueueJob(ScanJobType type, Map<String, String> parameters) {
        ScanJob job = new ScanJob(
                UUID.randomUUID().toString(),
                type,
                parameters,
                Instant.now(),
                policyFor(type).maxAttempts()
        );

        List<ScanJob> committedJobs = scanJobStore.updateAndGet(currentJobs -> {
            NormalizedQueueState normalizedState = normalizeStoredJobs(currentJobs);
            normalizedState.jobs().put(job.getId(), job);
            normalizedState.queuedJobIds().addLast(job.getId());
            return storedJobsOf(normalizedState.jobs(), normalizedState.queuedJobIds());
        });

        applyCommittedStoredJobs(committedJobs);
        log.info("Enqueued scan job {} ({})", job.getId(), job.getType());

        processQueue();
        queueLock.lock();
        try {
            return jobs.getOrDefault(job.getId(), job);
        } finally {
            queueLock.unlock();
        }
    }

    private List<ScanJob> mergeCurrentStateIntoStoredJobs(List<ScanJob> persistedJobs) {
        NormalizedQueueState persistedState = normalizeStoredJobs(persistedJobs);
        Map<String, ScanJob> mergedJobs = new LinkedHashMap<>(persistedState.jobs());
        for (ScanJob job : jobs.values()) {
            mergedJobs.put(job.getId(), job);
        }

        Deque<String> mergedQueuedJobIds = mergeQueuedJobIds(
                persistedState.queuedJobIds(),
                mergedJobs,
                queuedJobIds
        );
        return storedJobsOf(mergedJobs, mergedQueuedJobIds);
    }

    private Deque<String> mergeQueuedJobIds(
            Deque<String> persistedQueuedJobIds,
            Map<String, ScanJob> mergedJobs,
            Deque<String> localQueuedJobIds
    ) {
        Deque<String> mergedQueuedJobIds = new ArrayDeque<>();
        Set<String> queuedSeen = new HashSet<>();

        for (String jobId : persistedQueuedJobIds) {
            ScanJob job = mergedJobs.get(jobId);
            if (job == null || job.getStatus() != ScanJobStatus.QUEUED || !queuedSeen.add(jobId)) {
                continue;
            }
            mergedQueuedJobIds.addLast(jobId);
        }

        for (String jobId : localQueuedJobIds) {
            ScanJob job = mergedJobs.get(jobId);
            if (job == null || job.getStatus() != ScanJobStatus.QUEUED || !queuedSeen.add(jobId)) {
                continue;
            }
            mergedQueuedJobIds.addLast(jobId);
        }

        mergedJobs.values().stream()
                .filter(job -> job.getStatus() == ScanJobStatus.QUEUED)
                .sorted(JOB_CREATION_ORDER)
                .map(ScanJob::getId)
                .filter(queuedSeen::add)
                .forEach(mergedQueuedJobIds::addLast);
        return mergedQueuedJobIds;
    }

    private void applyNormalizedStateLocked(NormalizedQueueState state, boolean resetTransientState) {
        jobs.clear();
        jobs.putAll(state.jobs());
        queuedJobIds.clear();
        queuedJobIds.addAll(state.queuedJobIds());

        if (resetTransientState) {
            pollingJobIds.clear();
            startingJobIds.clear();
            return;
        }

        pollingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null || job.getStatus() != ScanJobStatus.RUNNING;
        });
        startingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null || job.getStatus() != ScanJobStatus.QUEUED;
        });
    }

    private NormalizedQueueState normalizeStoredJobs(List<ScanJob> persistedJobs) {
        Map<String, ScanJob> restoredJobs = new LinkedHashMap<>();
        Deque<String> restoredQueuedJobIds = new ArrayDeque<>();
        boolean normalized = false;
        int repairedRunningJobs = 0;

        if (persistedJobs == null || persistedJobs.isEmpty()) {
            return new NormalizedQueueState(restoredJobs, restoredQueuedJobIds, false, 0);
        }

        ArrayList<ScanJob> orderedJobs = new ArrayList<>(persistedJobs);
        orderedJobs.sort(JOB_CREATION_ORDER);
        for (ScanJob job : orderedJobs) {
            if (job == null || !isRestorable(job)) {
                normalized = true;
                continue;
            }
            restoredJobs.put(job.getId(), job);
        }

        List<ScanJob> queuedJobs = restoredJobs.values().stream()
                .filter(job -> job.getStatus() == ScanJobStatus.QUEUED)
                .sorted((left, right) -> {
                    int leftPosition = left.getQueuePosition() > 0 ? left.getQueuePosition() : Integer.MAX_VALUE;
                    int rightPosition = right.getQueuePosition() > 0 ? right.getQueuePosition() : Integer.MAX_VALUE;
                    int compare = Integer.compare(leftPosition, rightPosition);
                    if (compare != 0) {
                        return compare;
                    }
                    return JOB_CREATION_ORDER.compare(left, right);
                })
                .toList();

        int expectedQueuePosition = 1;
        for (ScanJob queuedJob : queuedJobs) {
            if (queuedJob.getQueuePosition() != expectedQueuePosition) {
                normalized = true;
            }
            queuedJob.assignQueuePosition(expectedQueuePosition);
            restoredQueuedJobIds.addLast(queuedJob.getId());
            expectedQueuePosition += 1;
        }

        List<ScanJob> nonQueuedJobs = restoredJobs.values().stream()
                .filter(job -> job.getStatus() != ScanJobStatus.QUEUED)
                .sorted(JOB_CREATION_ORDER)
                .toList();
        for (ScanJob nonQueuedJob : nonQueuedJobs) {
            if (nonQueuedJob.getQueuePosition() != 0) {
                normalized = true;
                nonQueuedJob.assignQueuePosition(0);
            }
        }

        for (ScanJob job : restoredJobs.values()) {
            if (job.getStatus() == ScanJobStatus.RUNNING && !hasText(job.getZapScanId())) {
                job.markFailed("Missing ZAP scan ID while restoring durable scan job state");
                repairedRunningJobs += 1;
            }
        }
        if (repairedRunningJobs > 0) {
            normalized = true;
        }

        return new NormalizedQueueState(restoredJobs, restoredQueuedJobIds, normalized, repairedRunningJobs);
    }

    private boolean isRestorable(ScanJob job) {
        return hasText(job.getId())
                && job.getType() != null
                && job.getParameters() != null
                && job.getCreatedAt() != null
                && job.getStatus() != null;
    }

    private void syncScanJobStoreLocked() {
        scanJobStore.upsertAll(storedJobsOf(jobs, queuedJobIds));
    }

    private List<ScanJob> storedJobsOf(Map<String, ScanJob> jobState, Deque<String> queuedState) {
        assignQueuePositions(jobState, queuedState);
        return jobState.values().stream()
                .sorted(JOB_CREATION_ORDER)
                .toList();
    }

    private void assignQueuePositions(Map<String, ScanJob> jobState, Deque<String> queuedState) {
        Set<String> queuedIds = new HashSet<>();
        int position = 1;
        for (String queuedJobId : queuedState) {
            ScanJob job = jobState.get(queuedJobId);
            if (job == null || job.getStatus() != ScanJobStatus.QUEUED) {
                continue;
            }
            job.assignQueuePosition(position++);
            queuedIds.add(queuedJobId);
        }

        for (ScanJob job : jobState.values()) {
            if (!queuedIds.contains(job.getId())) {
                job.assignQueuePosition(0);
            }
        }
    }

    private List<ScanJob> updateQueueState(UnaryOperator<NormalizedQueueState> mutator) {
        return scanJobStore.updateAndGet(currentJobs -> {
            NormalizedQueueState currentState = normalizeStoredJobs(currentJobs);
            NormalizedQueueState mutatedState = mutator.apply(currentState);
            NormalizedQueueState effectiveState = mutatedState != null ? mutatedState : currentState;
            return storedJobsOf(effectiveState.jobs(), effectiveState.queuedJobIds());
        });
    }

    private void applyCommittedStoredJobs(List<ScanJob> committedJobs) {
        queueLock.lock();
        try {
            applyNormalizedStateLocked(normalizeStoredJobs(committedJobs), false);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Build the next poll/start work plan based on capacity and retry timing.
     */
    private WorkPlan planWork() {
        queueLock.lock();
        try {
            boolean stateUpdated = false;
            List<PollTarget> pollTargets = new ArrayList<>();
            for (ScanJob job : jobs.values()) {
                if (job.getStatus() != ScanJobStatus.RUNNING) {
                    continue;
                }

                if (!hasText(job.getZapScanId())) {
                    job.markFailed("Missing ZAP scan ID while job is RUNNING");
                    stateUpdated = true;
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
                    stateUpdated = true;
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

            return new WorkPlan(pollTargets, startTargets, stateUpdated);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Execute runtime progress checks concurrently for running jobs.
     */
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

    /**
     * Execute startup actions concurrently for selected queued jobs.
     */
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

    /**
     * Apply async poll/start results to canonical in-memory queue state.
     */
    private ApplyOutcome applyResults(List<PollResult> pollResults, List<StartResult> startResults) {
        queueLock.lock();
        try {
            boolean stateUpdated = false;
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
                    stateUpdated = true;
                    continue;
                }

                int priorProgress = job.getLastKnownProgress();
                job.updateProgress(result.progress());
                if (job.getLastKnownProgress() != priorProgress) {
                    stateUpdated = true;
                }
                if (result.progress() >= 100) {
                    job.markSucceeded(result.progress());
                    log.info("Scan job {} completed successfully", job.getId());
                    stateUpdated = true;
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
                    stateUpdated = true;
                } else {
                    if (scheduleRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after startup error", job.getId());
                    } else {
                        log.error("Failed to start scan job {}: {}", job.getId(), result.error());
                    }
                    stateUpdated = true;
                }
            }

            return new ApplyOutcome(stopRequests, stateUpdated);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Queue retry when budget allows, otherwise transition job to FAILED.
     */
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

    /**
     * Count currently running jobs for the requested scan family.
     */
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

    /**
     * Count jobs currently being started for the requested scan family.
     */
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

    /**
     * Start a scan for a given job type and return the created scan ID.
     */
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

    /**
     * Read scan progress from appropriate active/spider service.
     */
    private int readProgress(ScanJobType type, String scanId) {
        return switch (type) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.getActiveScanProgressPercent(scanId);
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.getSpiderScanProgressPercent(scanId);
        };
    }

    /**
     * Stop a running scan by delegating to the matching service family.
     */
    private void executeStopRequest(StopRequest stopRequest) {
        switch (stopRequest.type()) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.stopActiveScanJob(stopRequest.scanId());
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.stopSpiderScanJob(stopRequest.scanId());
        }
    }

    /**
     * Parse optional list filter value into enum status.
     */
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

    /**
     * Return 1-based queue position, or -1 when not queued.
     */
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

    /**
     * Format a human-readable response for newly accepted jobs.
     */
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

    /**
     * Format detailed state for a single job lookup.
     */
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
        if (isDeadLetterJob(job)) {
            sb.append('\n').append("Dead Letter: true");
        }

        return sb.toString();
    }

    /**
     * Normalize blank strings to null for optional API parameters.
     */
    private String normalizeBlankToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * Resolve retry policy by scan family.
     */
    private RetryPolicy policyFor(ScanJobType type) {
        if (type.isActiveFamily()) {
            return activeRetryPolicy;
        }
        return spiderRetryPolicy;
    }

    /**
     * Return true when a job failed and exhausted attempts budget.
     */
    private boolean isDeadLetterJob(ScanJob job) {
        return job.getStatus() == ScanJobStatus.FAILED && job.getAttempts() >= job.getMaxAttempts();
    }

    /**
     * Require non-empty text and return trimmed value.
     */
    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    /**
     * Check whether a value contains non-whitespace text.
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Await async result and return fallback value on interruption/failure.
     */
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

    private record WorkPlan(List<PollTarget> pollTargets, List<StartTarget> startTargets, boolean stateUpdated) {
    }

    private record ApplyOutcome(List<StopRequest> stopRequests, boolean stateUpdated) {
    }

    private record PollTarget(String jobId, ScanJobType type, String scanId) {
    }

    private record StartTarget(String jobId, ScanJobType type, Map<String, String> parameters) {
    }

    private record StopRequest(ScanJobType type, String scanId) {
    }

    private record NormalizedQueueState(
            Map<String, ScanJob> jobs,
            Deque<String> queuedJobIds,
            boolean normalized,
            int repairedRunningJobs
    ) {
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

        /**
         * Sanitize configured retry policy values before runtime usage.
         */
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

        /**
         * Compute exponential backoff delay for a completed-attempt count.
         */
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
