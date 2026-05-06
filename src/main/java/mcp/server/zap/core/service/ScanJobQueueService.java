package mcp.server.zap.core.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.ScanJobAccessBoundary;
import mcp.server.zap.core.service.queue.ScanJobApplyOutcome;
import mcp.server.zap.core.service.queue.ScanJobClaimManager;
import mcp.server.zap.core.service.queue.ScanJobClaimMetrics;
import mcp.server.zap.core.service.queue.ScanJobDispatchResult;
import mcp.server.zap.core.service.queue.ScanJobDispatcher;
import mcp.server.zap.core.service.queue.ScanJobParameterNames;
import mcp.server.zap.core.service.queue.ScanJobQueueState;
import mcp.server.zap.core.service.queue.ScanJobQueueStateNormalizer;
import mcp.server.zap.core.service.queue.ScanJobResultApplier;
import mcp.server.zap.core.service.queue.ScanJobResponseFormatter;
import mcp.server.zap.core.service.queue.ScanJobRetryPolicy;
import mcp.server.zap.core.service.queue.ScanJobRuntimeExecutor;
import mcp.server.zap.core.service.queue.ScanJobStopRequest;
import mcp.server.zap.core.service.queue.ScanJobWorkPlan;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

@Service
public class ScanJobQueueService {
    private static final Logger log = LoggerFactory.getLogger(ScanJobQueueService.class);

    private static final String PARAM_TARGET_URL = ScanJobParameterNames.TARGET_URL;
    private static final String PARAM_RECURSE = ScanJobParameterNames.RECURSE;
    private static final String PARAM_POLICY = ScanJobParameterNames.POLICY;
    private static final String PARAM_CONTEXT_ID = ScanJobParameterNames.CONTEXT_ID;
    private static final String PARAM_USER_ID = ScanJobParameterNames.USER_ID;
    private static final String PARAM_MAX_CHILDREN = ScanJobParameterNames.MAX_CHILDREN;
    private static final String PARAM_SUBTREE_ONLY = ScanJobParameterNames.SUBTREE_ONLY;
    private static final String PARAM_REPLAY_OF_JOB_ID = ScanJobParameterNames.REPLAY_OF_JOB_ID;
    private static final String PARAM_IDEMPOTENCY_KEY = ScanJobParameterNames.IDEMPOTENCY_KEY;
    private static final String DEFAULT_REQUESTER_ID = "anonymous";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final long DEFAULT_CLAIM_LEASE_MS = 15000L;

    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private final ScanJobRetryPolicy activeRetryPolicy;
    private final ScanJobRetryPolicy spiderRetryPolicy;
    private final ScanJobStore scanJobStore;
    private final QueueLeadershipCoordinator queueLeadershipCoordinator;
    private final ScanJobResponseFormatter responseFormatter;
    private final ScanJobQueueStateNormalizer queueStateNormalizer = new ScanJobQueueStateNormalizer();
    private final ScanJobDispatcher dispatcher;
    private final String workerNodeId;

    private long claimLeaseMs = DEFAULT_CLAIM_LEASE_MS;
    private ScanJobClaimManager claimManager;
    private ScanJobResultApplier resultApplier;
    private QueueStateMetrics queueStateMetrics = QueueStateMetrics.noop();
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ScanJobAccessBoundary scanJobAccessBoundary;

    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final Deque<String> queuedJobIds = new ArrayDeque<>();
    private final ReentrantLock queueLock = new ReentrantLock();

    @Autowired
    public ScanJobQueueService(ActiveScanService activeScanService,
                               SpiderScanService spiderScanService,
                               ObjectProvider<AjaxSpiderService> ajaxSpiderServiceProvider,
                               UrlValidationService urlValidationService,
                               ScanLimitProperties scanLimitProperties,
                               ObjectProvider<ScanJobStore> scanJobStoreProvider,
                               ObjectProvider<QueueLeadershipCoordinator> queueLeadershipCoordinatorProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider,
                               @Value("${zap.scan.queue.virtual-threads.enabled:false}") boolean virtualThreadsEnabled,
                               @Value("${zap.scan.queue.claim-lease-ms:15000}") long claimLeaseMs,
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
                ajaxSpiderServiceProvider.getIfAvailable(),
                urlValidationService,
                scanLimitProperties,
                new RetryPolicy(activeMaxAttempts, activeInitialBackoffMs, activeMaxBackoffMs, activeBackoffMultiplier),
                new RetryPolicy(spiderMaxAttempts, spiderInitialBackoffMs, spiderMaxBackoffMs, spiderBackoffMultiplier),
                virtualThreadsEnabled,
                scanJobStoreProvider.getIfAvailable(InMemoryScanJobStore::new),
                queueLeadershipCoordinatorProvider.getIfAvailable(SingleNodeQueueLeadershipCoordinator::new)
        );
        this.claimLeaseMs = sanitizeClaimLeaseMs(claimLeaseMs);
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.claimManager = new ScanJobClaimManager(
                this.scanJobStore,
                this.workerNodeId,
                ScanJobClaimMetrics.create(meterRegistry)
        );
        this.resultApplier = new ScanJobResultApplier(
                this.scanJobStore,
                this.workerNodeId,
                this.claimManager,
                this.activeRetryPolicy,
                this.spiderRetryPolicy
        );
        this.queueStateMetrics = QueueStateMetrics.create(meterRegistry);
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setScanJobAccessBoundary(ScanJobAccessBoundary scanJobAccessBoundary) {
        this.scanJobAccessBoundary = scanJobAccessBoundary;
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
                null,
                urlValidationService,
                scanLimitProperties,
                maxAttempts,
                virtualThreadsEnabled
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
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
                null,
                urlValidationService,
                scanLimitProperties,
                maxAttempts,
                virtualThreadsEnabled,
                scanJobStore
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
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
                null,
                urlValidationService,
                scanLimitProperties,
                maxAttempts,
                virtualThreadsEnabled,
                scanJobStore,
                queueLeadershipCoordinator
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        int maxAttempts,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore,
                        QueueLeadershipCoordinator queueLeadershipCoordinator) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
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
                null,
                urlValidationService,
                scanLimitProperties,
                activeRetryPolicy,
                spiderRetryPolicy,
                virtualThreadsEnabled
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
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
                null,
                urlValidationService,
                scanLimitProperties,
                activeRetryPolicy,
                spiderRetryPolicy,
                virtualThreadsEnabled,
                scanJobStore
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderService,
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
        this(
                activeScanService,
                spiderScanService,
                null,
                urlValidationService,
                scanLimitProperties,
                activeRetryPolicy,
                spiderRetryPolicy,
                virtualThreadsEnabled,
                scanJobStore,
                queueLeadershipCoordinator
        );
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        UrlValidationService urlValidationService,
                        ScanLimitProperties scanLimitProperties,
                        RetryPolicy activeRetryPolicy,
                        RetryPolicy spiderRetryPolicy,
                        boolean virtualThreadsEnabled,
                        ScanJobStore scanJobStore,
                        QueueLeadershipCoordinator queueLeadershipCoordinator) {
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
        this.activeRetryPolicy = activeRetryPolicy.sanitized();
        this.spiderRetryPolicy = spiderRetryPolicy.sanitized();
        this.scanJobStore = scanJobStore != null ? scanJobStore : new InMemoryScanJobStore();
        this.queueLeadershipCoordinator = queueLeadershipCoordinator != null
                ? queueLeadershipCoordinator
                : new SingleNodeQueueLeadershipCoordinator();
        this.responseFormatter = new ScanJobResponseFormatter();
        ScanJobRuntimeExecutor runtimeExecutor = new ScanJobRuntimeExecutor(
                activeScanService,
                spiderScanService,
                ajaxSpiderService
        );
        this.dispatcher = ScanJobDispatcher.create(runtimeExecutor, virtualThreadsEnabled);
        this.workerNodeId = sanitizeWorkerNodeId(this.queueLeadershipCoordinator.nodeId());
        this.claimManager = new ScanJobClaimManager(this.scanJobStore, this.workerNodeId, ScanJobClaimMetrics.noop());
        this.resultApplier = new ScanJobResultApplier(
                this.scanJobStore,
                this.workerNodeId,
                this.claimManager,
                this.activeRetryPolicy,
                this.spiderRetryPolicy
        );

        log.info("Scan queue retry policy active={} spider={} workerNodeId={}",
                this.activeRetryPolicy,
                this.spiderRetryPolicy,
                this.workerNodeId);
        restoreStateFromStore();
    }

    @PreDestroy
    void shutdownExecutor() {
        dispatcher.close();
        queueLeadershipCoordinator.close();
    }

    public String queueActiveScan(
            String targetUrl,
            String recurse,
            String policy,
            String idempotencyKey
    ) {
        Map<String, String> parameters = targetParameters(targetUrl);
        parameters.put(PARAM_RECURSE, trimToDefault(recurse, "true"));
        parameters.put(PARAM_POLICY, trimToDefault(policy, ""));
        return submitQueuedScan(ScanJobType.ACTIVE_SCAN, parameters, idempotencyKey);
    }

    public String queueActiveScanAsUser(
            String contextId,
            String userId,
            String targetUrl,
            String recurse,
            String policy,
            String idempotencyKey
    ) {
        Map<String, String> parameters = userScopedTargetParameters(contextId, userId, targetUrl);
        parameters.put(PARAM_RECURSE, trimToDefault(recurse, "true"));
        parameters.put(PARAM_POLICY, trimToDefault(policy, ""));
        return submitQueuedScan(ScanJobType.ACTIVE_SCAN_AS_USER, parameters, idempotencyKey);
    }

    public String queueSpiderScan(
            String targetUrl,
            String idempotencyKey
    ) {
        return submitQueuedScan(ScanJobType.SPIDER_SCAN, targetParameters(targetUrl), idempotencyKey);
    }

    public String queueAjaxSpiderScan(String targetUrl, String idempotencyKey) {
        return submitQueuedScan(ScanJobType.AJAX_SPIDER, targetParameters(targetUrl), idempotencyKey);
    }

    public String queueSpiderScanAsUser(
            String contextId,
            String userId,
            String targetUrl,
            String maxChildren,
            String recurse,
            String subtreeOnly,
            String idempotencyKey
    ) {
        Map<String, String> parameters = userScopedTargetParameters(contextId, userId, targetUrl);
        parameters.put(PARAM_MAX_CHILDREN, trimToDefault(maxChildren, ""));
        parameters.put(PARAM_RECURSE, trimToDefault(recurse, "true"));
        parameters.put(PARAM_SUBTREE_ONLY, trimToDefault(subtreeOnly, "false"));
        return submitQueuedScan(ScanJobType.SPIDER_SCAN_AS_USER, parameters, idempotencyKey);
    }

    public String getScanJobStatus(
            String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        processQueue();

        ScanJob job = scanJobStore.load(normalizedJobId).orElse(null);
        job = requireVisibleJob(normalizedJobId, job);
        return responseFormatter.formatJobDetail(job, job.getQueuePosition(), Instant.now());
    }

    public String listScanJobs(
            String statusFilter
    ) {
        ScanJobStatus filter = parseStatusFilter(statusFilter);
        processQueue();

        List<ScanJob> snapshot = scanJobStore.list().stream()
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList();
        snapshot = filterVisibleJobs(snapshot);
        return responseFormatter.formatJobList(snapshot, filter, Instant.now());
    }

    /**
     * Return the current durable job snapshot for runtime protection and diagnostics.
     */
    public List<ScanJob> listJobsSnapshot() {
        processQueue();
        return scanJobStore.list().stream()
                .sorted(Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId))
                .toList();
    }

    public String cancelScanJob(
            String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        ScanJobStopRequest[] stopRequest = new ScanJobStopRequest[1];
        String[] response = new String[1];

        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob job = currentState.jobs().get(normalizedJobId);
            job = requireVisibleJob(normalizedJobId, job);

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
                    stopRequest[0] = new ScanJobStopRequest(job.getType(), job.getZapScanId());
                }
                response[0] = "Scan job cancelled: " + normalizedJobId + " (was RUNNING)";
                return currentState;
            }

            response[0] = "Scan job " + normalizedJobId + " is already terminal with status " + job.getStatus() + ".";
            return currentState;
        });
        applyCommittedStoredJobs(committedJobs);

        if (stopRequest[0] != null) {
            dispatcher.executeStopRequest(stopRequest[0]);
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

    public String retryScanJob(
            String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob job = currentState.jobs().get(normalizedJobId);
            job = requireVisibleJob(normalizedJobId, job);

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

    /**
     * List jobs that exhausted retry budget and remain in FAILED state.
     */
    public String listDeadLetterJobs() {
        processQueue();

        List<ScanJob> deadLetterJobs = scanJobStore.list().stream()
                .filter(this::isDeadLetterJob)
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList();
        deadLetterJobs = filterVisibleJobs(deadLetterJobs);

        return responseFormatter.formatDeadLetterJobs(deadLetterJobs);
    }

    public String requeueDeadLetterJob(
            String jobId
    ) {
        String normalizedJobId = requireText(jobId, "jobId");
        ScanJob[] replayJob = new ScanJob[1];
        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob deadLetterJob = currentState.jobs().get(normalizedJobId);
            deadLetterJob = requireVisibleJob(normalizedJobId, deadLetterJob);
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
                    policyFor(deadLetterJob.getType()).maxAttempts(),
                    deadLetterJob.getRequesterId(),
                    null
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
        observeLeadership();
        restoreStateFromStore(false);
        Instant now = Instant.now();
        Instant claimUntil = now.plusMillis(claimLeaseMs);
        claimManager.renewInFlightClaims(now, claimUntil);

        ScanJobWorkPlan workPlan = claimManager.claimWork(
                snapshotJobsForClaimObservation(),
                scanLimitProperties.getMaxConcurrentActiveScans(),
                scanLimitProperties.getMaxConcurrentSpiderScans(),
                now,
                claimUntil
        );
        if (workPlan.pollTargets().isEmpty() && workPlan.startTargets().isEmpty()) {
            applyCommittedStoredJobs(scanJobStore.list());
            return;
        }

        ScanJobDispatchResult dispatchResult = dispatcher.dispatch(workPlan);
        ScanJobApplyOutcome applyOutcome = resultApplier.applyResults(
                dispatchResult.pollResults(),
                dispatchResult.startResults(),
                claimUntil
        );
        dispatcher.executeStopRequests(applyOutcome.stopRequests());
        if (applyOutcome.persistenceFailure() != null) {
            throw applyOutcome.persistenceFailure();
        }
        applyCommittedStoredJobs(scanJobStore.list());
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
     * Continue evaluating optional coordinator leadership for observability only.
     */
    private void observeLeadership() {
        LeadershipDecision leadershipDecision = queueLeadershipCoordinator.evaluateLeadership();
        if (leadershipDecision.acquiredLeadership()) {
            log.info("Queue maintenance leadership acquired on node {}. Normal scan dispatch now uses durable job claims.",
                    workerNodeId);
        }
        if (leadershipDecision.lostLeadership()) {
            log.warn("Queue maintenance leadership lost on node {}. Claim-based dispatch remains active on all replicas.",
                    workerNodeId);
        }
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

        ScanJobQueueState normalizedState = queueStateNormalizer.normalize(persistedJobs);

        queueLock.lock();
        try {
            applyNormalizedStateLocked(normalizedState, true, Instant.now());
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
     * Create a new queued job and trigger dispatch loop.
     */
    private AdmittedJob enqueueJob(ScanJobType type, Map<String, String> parameters, String idempotencyKey) {
        String requesterId = resolveRequesterId();
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        ScanJob candidate = new ScanJob(
                UUID.randomUUID().toString(),
                type,
                parameters,
                Instant.now(),
                policyFor(type).maxAttempts(),
                requesterId,
                normalizedIdempotencyKey
        );

        String[] admittedJobId = new String[1];
        boolean[] idempotentReplay = new boolean[1];

        ScanJob admitted = scanJobStore.admitQueuedJob(candidate);
        admittedJobId[0] = admitted.getId();
        idempotentReplay[0] = !candidate.getId().equals(admitted.getId());
        if (idempotentReplay[0]) {
            validateIdempotentReplay(admitted, candidate);
        }
        List<ScanJob> committedJobs = scanJobStore.list();

        applyCommittedStoredJobs(committedJobs);

        if (idempotentReplay[0]) {
            log.info(
                    "Reused existing scan job {} for requester {} idempotency key {}",
                    admittedJobId[0],
                    requesterId,
                    normalizedIdempotencyKey
            );
        } else {
            log.info("Enqueued scan job {} ({})", admittedJobId[0], type);
        }

        processQueue();
        queueLock.lock();
        try {
            ScanJob admittedJob = jobs.get(admittedJobId[0]);
            if (admittedJob == null) {
                admittedJob = committedJobs.stream()
                        .filter(job -> job != null && job.getId().equals(admittedJobId[0]))
                        .findFirst()
                        .orElse(candidate);
            }
            return new AdmittedJob(admittedJob, idempotentReplay[0]);
        } finally {
            queueLock.unlock();
        }
    }

    private String submitQueuedScan(ScanJobType type, Map<String, String> parameters, String idempotencyKey) {
        AdmittedJob admission = enqueueJob(type, parameters, idempotencyKey);
        return responseFormatter.formatSubmission(admission.job(), admission.idempotentReplay(), Instant.now());
    }

    private List<ScanJob> filterVisibleJobs(List<ScanJob> jobs) {
        if (scanJobAccessBoundary == null) {
            return jobs == null ? List.of() : jobs;
        }
        return scanJobAccessBoundary.filterVisibleJobs(jobs);
    }

    private ScanJob requireVisibleJob(String jobId, ScanJob job) {
        if (job == null || (scanJobAccessBoundary != null && !scanJobAccessBoundary.canCurrentRequesterAccess(job))) {
            throw new IllegalArgumentException("No scan job found for ID: " + jobId);
        }
        return job;
    }

    private void validateIdempotentReplay(ScanJob existingJob, ScanJob requestedJob) {
        if (existingJob.getType() != requestedJob.getType()
                || !existingJob.getParameters().equals(requestedJob.getParameters())) {
            throw new IllegalStateException(
                    "Idempotency key '" + requestedJob.getIdempotencyKey()
                            + "' has already been used for a different queued scan request."
            );
        }
    }

    private void applyNormalizedStateLocked(ScanJobQueueState state, boolean resetTransientState, Instant now) {
        jobs.clear();
        jobs.putAll(state.jobs());
        queuedJobIds.clear();
        queuedJobIds.addAll(state.queuedJobIds());
        queueStateMetrics.refresh(state.jobs().values(), now);

        if (resetTransientState) {
            claimManager.resetInFlightClaims();
            return;
        }

        claimManager.retainValidInFlightClaims(jobs, now);
    }

    private void syncScanJobStoreLocked() {
        scanJobStore.upsertAll(queueStateNormalizer.storedJobsOf(jobs, queuedJobIds));
    }

    private List<ScanJob> updateQueueState(UnaryOperator<ScanJobQueueState> mutator) {
        return scanJobStore.updateAndGet(currentJobs -> {
            ScanJobQueueState currentState = queueStateNormalizer.normalize(currentJobs);
            ScanJobQueueState mutatedState = mutator.apply(currentState);
            ScanJobQueueState effectiveState = mutatedState != null ? mutatedState : currentState;
            return queueStateNormalizer.storedJobsOf(effectiveState.jobs(), effectiveState.queuedJobIds());
        });
    }

    private void applyCommittedStoredJobs(List<ScanJob> committedJobs) {
        queueLock.lock();
        try {
            applyNormalizedStateLocked(queueStateNormalizer.normalize(committedJobs), false, Instant.now());
        } finally {
            queueLock.unlock();
        }
    }

    private List<ScanJob> snapshotJobsForClaimObservation() {
        queueLock.lock();
        try {
            return new ArrayList<>(claimManager.copyJobs(jobs.values()));
        } finally {
            queueLock.unlock();
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
     * Normalize blank strings to null for optional API parameters.
     */
    private String normalizeBlankToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = normalizeBlankToNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    PARAM_IDEMPOTENCY_KEY + " must be " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters or fewer"
            );
        }
        return normalized;
    }

    private String resolveRequesterId() {
        if (clientWorkspaceResolver == null) {
            return DEFAULT_REQUESTER_ID;
        }
        return clientWorkspaceResolver.resolveCurrentClientId();
    }

    /**
     * Resolve retry policy by scan family.
     */
    private ScanJobRetryPolicy policyFor(ScanJobType type) {
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

    private String sanitizeWorkerNodeId(String workerNodeId) {
        if (!hasText(workerNodeId)) {
            return "mcp-zap-node";
        }
        return workerNodeId.trim();
    }

    private long sanitizeClaimLeaseMs(long claimLeaseMs) {
        return Math.max(5000L, claimLeaseMs);
    }

    private Map<String, String> targetParameters(String targetUrl) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_TARGET_URL, normalizeTargetUrl(targetUrl));
        return parameters;
    }

    private Map<String, String> userScopedTargetParameters(String contextId, String userId, String targetUrl) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_CONTEXT_ID, requireText(contextId, PARAM_CONTEXT_ID));
        parameters.put(PARAM_USER_ID, requireText(userId, PARAM_USER_ID));
        parameters.put(PARAM_TARGET_URL, normalizeTargetUrl(targetUrl));
        return parameters;
    }

    private String normalizeTargetUrl(String targetUrl) {
        String normalizedTarget = requireText(targetUrl, PARAM_TARGET_URL);
        urlValidationService.validateUrl(normalizedTarget);
        return normalizedTarget;
    }

    private String trimToDefault(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
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

    private record AdmittedJob(ScanJob job, boolean idempotentReplay) {
    }

    static final class RetryPolicy extends ScanJobRetryPolicy {
        RetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs, double multiplier) {
            super(maxAttempts, initialBackoffMs, maxBackoffMs, multiplier);
        }
    }

    private static final class QueueStateMetrics {
        private final Map<ScanJobStatus, AtomicInteger> jobStatusGauges;
        private final AtomicInteger activeClaimsGauge;
        private final AtomicInteger expiredClaimsGauge;

        private QueueStateMetrics(Map<ScanJobStatus, AtomicInteger> jobStatusGauges,
                                  AtomicInteger activeClaimsGauge,
                                  AtomicInteger expiredClaimsGauge) {
            this.jobStatusGauges = jobStatusGauges;
            this.activeClaimsGauge = activeClaimsGauge;
            this.expiredClaimsGauge = expiredClaimsGauge;
        }

        static QueueStateMetrics create(MeterRegistry meterRegistry) {
            if (meterRegistry == null) {
                return noop();
            }

            Map<ScanJobStatus, AtomicInteger> statusGauges = new LinkedHashMap<>();
            for (ScanJobStatus status : ScanJobStatus.values()) {
                AtomicInteger gauge = meterRegistry.gauge(
                        "mcp.zap.queue.jobs",
                        List.of(io.micrometer.core.instrument.Tag.of("status", status.name().toLowerCase(Locale.ROOT))),
                        new AtomicInteger(0)
                );
                statusGauges.put(status, gauge);
            }

            AtomicInteger activeClaimsGauge = meterRegistry.gauge(
                    "mcp.zap.queue.claims",
                    List.of(io.micrometer.core.instrument.Tag.of("state", "active")),
                    new AtomicInteger(0)
            );
            AtomicInteger expiredClaimsGauge = meterRegistry.gauge(
                    "mcp.zap.queue.claims",
                    List.of(io.micrometer.core.instrument.Tag.of("state", "expired")),
                    new AtomicInteger(0)
            );
            return new QueueStateMetrics(statusGauges, activeClaimsGauge, expiredClaimsGauge);
        }

        static QueueStateMetrics noop() {
            return new QueueStateMetrics(Map.of(), null, null);
        }

        void refresh(Iterable<ScanJob> jobs, Instant now) {
            if (jobStatusGauges.isEmpty()) {
                return;
            }

            Map<ScanJobStatus, Integer> counts = new LinkedHashMap<>();
            for (ScanJobStatus status : ScanJobStatus.values()) {
                counts.put(status, 0);
            }

            int activeClaims = 0;
            int expiredClaims = 0;
            for (ScanJob job : jobs) {
                if (job == null || job.getStatus() == null) {
                    continue;
                }
                counts.computeIfPresent(job.getStatus(), (status, count) -> count + 1);
                if (job.getClaimExpiresAt() != null) {
                    if (job.claimExpiredAt(now)) {
                        expiredClaims++;
                    } else {
                        activeClaims++;
                    }
                }
            }

            for (Map.Entry<ScanJobStatus, AtomicInteger> entry : jobStatusGauges.entrySet()) {
                entry.getValue().set(counts.getOrDefault(entry.getKey(), 0));
            }
            if (activeClaimsGauge != null) {
                activeClaimsGauge.set(activeClaims);
            }
            if (expiredClaimsGauge != null) {
                expiredClaimsGauge.set(expiredClaims);
            }
        }
    }
}
