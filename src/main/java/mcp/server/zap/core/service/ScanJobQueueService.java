package mcp.server.zap.core.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.queue.leadership.LeadershipDecision;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final String PARAM_IDEMPOTENCY_KEY = "idempotencyKey";
    private static final String DEFAULT_REQUESTER_ID = "anonymous";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final long DEFAULT_CLAIM_LEASE_MS = 15000L;

    private static final AtomicInteger PLATFORM_THREAD_COUNTER = new AtomicInteger(0);
    private static final Comparator<ScanJob> JOB_CREATION_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    private final ActiveScanService activeScanService;
    private final SpiderScanService spiderScanService;
    private final AjaxSpiderService ajaxSpiderService;
    private final UrlValidationService urlValidationService;
    private final ScanLimitProperties scanLimitProperties;
    private final RetryPolicy activeRetryPolicy;
    private final RetryPolicy spiderRetryPolicy;
    private final ScanJobStore scanJobStore;
    private final QueueLeadershipCoordinator queueLeadershipCoordinator;
    private final String workerNodeId;

    private final ExecutorService ioExecutor;
    private long claimLeaseMs = DEFAULT_CLAIM_LEASE_MS;
    private ClaimMetrics claimMetrics = ClaimMetrics.noop();
    private QueueStateMetrics queueStateMetrics = QueueStateMetrics.noop();
    private ClientWorkspaceResolver clientWorkspaceResolver;

    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final Deque<String> queuedJobIds = new ArrayDeque<>();
    private final Set<String> pollingJobIds = new HashSet<>();
    private final Set<String> startingJobIds = new HashSet<>();
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
        this.claimMetrics = ClaimMetrics.create(meterRegistry);
        this.queueStateMetrics = QueueStateMetrics.create(meterRegistry);
    }

    @Autowired(required = false)
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
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
        this.activeScanService = activeScanService;
        this.spiderScanService = spiderScanService;
        this.ajaxSpiderService = ajaxSpiderService;
        this.urlValidationService = urlValidationService;
        this.scanLimitProperties = scanLimitProperties;
        this.activeRetryPolicy = activeRetryPolicy.sanitized();
        this.spiderRetryPolicy = spiderRetryPolicy.sanitized();
        this.scanJobStore = scanJobStore != null ? scanJobStore : new InMemoryScanJobStore();
        this.queueLeadershipCoordinator = queueLeadershipCoordinator != null
                ? queueLeadershipCoordinator
                : new SingleNodeQueueLeadershipCoordinator();
        this.workerNodeId = sanitizeWorkerNodeId(this.queueLeadershipCoordinator.nodeId());

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

        log.info("Scan queue retry policy active={} spider={} workerNodeId={}",
                this.activeRetryPolicy,
                this.spiderRetryPolicy,
                this.workerNodeId);
        restoreStateFromStore();
    }

    @PreDestroy
    void shutdownExecutor() {
        ioExecutor.shutdownNow();
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
        if (job == null) {
            throw new IllegalArgumentException("No scan job found for ID: " + normalizedJobId);
        }
        return formatJobDetail(job, job.getQueuePosition());
    }

    public String listScanJobs(
            String statusFilter
    ) {
        ScanJobStatus filter = parseStatusFilter(statusFilter);
        processQueue();

        List<ScanJob> snapshot = scanJobStore.list().stream()
                .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                .toList();
        Instant now = Instant.now();

        StringBuilder output = new StringBuilder();
        output.append("Scan job summary")
                .append('\n')
                .append("Total jobs: ")
                .append(snapshot.size())
                .append('\n')
                .append("Queue depth: ")
                .append(snapshot.stream().filter(job -> job.getStatus() == ScanJobStatus.QUEUED).count())
                .append('\n')
                .append("Claimed for Dispatch: ")
                .append(snapshot.stream().filter(job -> job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)).count())
                .append('\n')
                .append("Running: ")
                .append(snapshot.stream().filter(job -> job.getStatus() == ScanJobStatus.RUNNING).count())
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
            appendClaimSummary(output, job, now);
            output.append('\n');
        }

        if (visible == 0) {
            output.append("No jobs match current filter.");
        }

        return output.toString();
    }

    /**
     * Return the current durable job snapshot for runtime protection and diagnostics.
     */
    public List<ScanJob> listJobsSnapshot() {
        processQueue();
        return scanJobStore.list().stream()
                .sorted(JOB_CREATION_ORDER)
                .toList();
    }

    public String cancelScanJob(
            String jobId
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

    public String retryScanJob(
            String jobId
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

    public String requeueDeadLetterJob(
            String jobId
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
        renewInFlightClaims(now, claimUntil);

        WorkPlan workPlan = claimWork(now, claimUntil);
        if (workPlan.pollTargets().isEmpty() && workPlan.startTargets().isEmpty()) {
            applyCommittedStoredJobs(scanJobStore.list());
            return;
        }

        List<PollResult> pollResults = executePollTargets(workPlan.pollTargets());
        List<StartResult> startResults = executeStartTargets(workPlan.startTargets());
        ApplyOutcome applyOutcome = applyClaimedResults(pollResults, startResults, claimUntil);
        applyCommittedStoredJobs(scanJobStore.list());

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

    private void renewInFlightClaims(Instant now, Instant claimUntil) {
        queueLock.lock();
        try {
            if (startingJobIds.isEmpty() && pollingJobIds.isEmpty()) {
                return;
            }
            Set<String> inFlightJobIds = new HashSet<>(startingJobIds);
            inFlightJobIds.addAll(pollingJobIds);
            scanJobStore.renewClaims(workerNodeId, inFlightJobIds, now, claimUntil);
            claimMetrics.recordRenewedClaims(inFlightJobIds.size());
        } finally {
            queueLock.unlock();
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
        return formatSubmission(admission.job(), admission.idempotentReplay());
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

    private void applyNormalizedStateLocked(NormalizedQueueState state, boolean resetTransientState) {
        jobs.clear();
        jobs.putAll(state.jobs());
        queuedJobIds.clear();
        queuedJobIds.addAll(state.queuedJobIds());
        queueStateMetrics.refresh(state.jobs().values(), Instant.now());

        if (resetTransientState) {
            pollingJobIds.clear();
            startingJobIds.clear();
            return;
        }

        pollingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null
                    || job.getStatus() != ScanJobStatus.RUNNING
                    || !job.isClaimedBy(workerNodeId);
        });
        startingJobIds.removeIf(jobId -> {
            ScanJob job = jobs.get(jobId);
            return job == null
                    || job.getStatus() != ScanJobStatus.QUEUED
                    || !job.isClaimedBy(workerNodeId);
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
     * Claim durable running and queued work for this replica.
     */
    private WorkPlan claimWork(Instant now, Instant claimUntil) {
        Map<String, ScanJob> priorJobs = snapshotJobsForClaimObservation();
        List<ScanJob> runningJobs = scanJobStore.claimRunningJobs(workerNodeId, now, claimUntil);
        List<ScanJob> queuedJobs = scanJobStore.claimQueuedJobs(
                workerNodeId,
                now,
                claimUntil,
                scanLimitProperties.getMaxConcurrentActiveScans(),
                scanLimitProperties.getMaxConcurrentSpiderScans()
        );
        recordClaimObservations(priorJobs, runningJobs, queuedJobs, now);

        queueLock.lock();
        try {
            List<PollTarget> pollTargets = new ArrayList<>();
            for (ScanJob job : runningJobs) {
                if (!hasText(job.getZapScanId())) {
                    scanJobStore.updateClaimedJob(job.getId(), workerNodeId, claimedJob -> {
                        claimedJob.markFailed("Missing ZAP scan ID while job is RUNNING");
                        return claimedJob;
                    });
                    continue;
                }
                if (pollingJobIds.add(job.getId())) {
                    pollTargets.add(new PollTarget(job.getId(), job.getType(), job.getZapScanId()));
                }
            }

            List<StartTarget> startTargets = new ArrayList<>();
            for (ScanJob job : queuedJobs) {
                if (startingJobIds.add(job.getId())) {
                    startTargets.add(new StartTarget(job.getId(), job.getType(), job.getParameters()));
                }
            }
            return new WorkPlan(pollTargets, startTargets);
        } finally {
            queueLock.unlock();
        }
    }

    private Map<String, ScanJob> snapshotJobsForClaimObservation() {
        queueLock.lock();
        try {
            Map<String, ScanJob> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, ScanJob> entry : jobs.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                snapshot.put(entry.getKey(), copyJob(entry.getValue()));
            }
            return snapshot;
        } finally {
            queueLock.unlock();
        }
    }

    private ScanJob copyJob(ScanJob job) {
        return ScanJob.restore(
                job.getId(),
                job.getType(),
                job.getParameters(),
                job.getCreatedAt(),
                job.getMaxAttempts(),
                job.getRequesterId(),
                job.getIdempotencyKey(),
                job.getStatus(),
                job.getAttempts(),
                job.getZapScanId(),
                job.getLastError(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getNextAttemptAt(),
                job.getLastKnownProgress(),
                job.getQueuePosition(),
                job.getClaimOwnerId(),
                job.getClaimHeartbeatAt(),
                job.getClaimExpiresAt()
        );
    }

    private void recordClaimObservations(
            Map<String, ScanJob> priorJobs,
            List<ScanJob> runningJobs,
            List<ScanJob> queuedJobs,
            Instant now
    ) {
        claimMetrics.recordQueuedClaims(queuedJobs.size());

        for (ScanJob runningJob : runningJobs) {
            ScanJob priorJob = priorJobs.get(runningJob.getId());
            if (priorJob == null || priorJob.getStatus() != ScanJobStatus.RUNNING) {
                continue;
            }
            if (hasText(priorJob.getClaimOwnerId()) && !workerNodeId.equals(priorJob.getClaimOwnerId())) {
                claimMetrics.recordRunningRecoveries(1);
                log.info(
                        "Recovered running scan job {} claim from worker {} on worker {} (previous expiry={})",
                        runningJob.getId(),
                        priorJob.getClaimOwnerId(),
                        workerNodeId,
                        priorJob.getClaimExpiresAt()
                );
                continue;
            }
            if (!hasText(priorJob.getClaimOwnerId()) && runningJob.isClaimedBy(workerNodeId)) {
                log.info("Claimed previously unowned running scan job {} on worker {}", runningJob.getId(), workerNodeId);
            }
            if (priorJob.claimExpiredAt(now) && workerNodeId.equals(runningJob.getClaimOwnerId())) {
                claimMetrics.recordExpiredClaimRecoveries(1);
            }
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
     * Apply async poll/start results only if this replica still owns the durable claim.
     */
    private ApplyOutcome applyClaimedResults(
            List<PollResult> pollResults,
            List<StartResult> startResults,
            Instant claimUntil
    ) {
        List<StopRequest> stopRequests = new ArrayList<>();

        for (PollResult result : pollResults) {
            queueLock.lock();
            try {
                pollingJobIds.remove(result.jobId());
            } finally {
                queueLock.unlock();
            }

            ScanJob updatedJob = scanJobStore.updateClaimedJob(result.jobId(), workerNodeId, job -> {
                if (job.getStatus() != ScanJobStatus.RUNNING) {
                    return job;
                }

                if (!result.success()) {
                    if (scheduleRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after polling error", job.getId());
                    } else {
                        log.warn("Marking scan job {} as FAILED during status refresh: {}", job.getId(), result.error());
                    }
                    return job;
                }

                job.updateProgress(result.progress());
                if (result.progress() >= 100) {
                    job.markSucceeded(result.progress());
                    log.info("Scan job {} completed successfully", job.getId());
                } else {
                    job.claim(workerNodeId, Instant.now(), claimUntil);
                }
                return job;
            }).orElse(null);

            if (updatedJob == null) {
                claimMetrics.recordClaimConflicts(1);
                log.debug("Skipping polling result for job {} because this replica no longer owns the claim", result.jobId());
            }
        }

        for (StartResult result : startResults) {
            queueLock.lock();
            try {
                startingJobIds.remove(result.jobId());
            } finally {
                queueLock.unlock();
            }

            ScanJob updatedJob = scanJobStore.updateClaimedJob(result.jobId(), workerNodeId, job -> {
                if (job.getStatus() != ScanJobStatus.QUEUED) {
                    return job;
                }

                job.incrementAttempts();
                if (result.success()) {
                    job.markRunning(result.scanId());
                    job.claim(workerNodeId, Instant.now(), claimUntil);
                    log.info("Started scan job {} as ZAP scan {} on worker {}", job.getId(), result.scanId(), workerNodeId);
                } else {
                    if (scheduleRetryIfAllowed(job, result.error())) {
                        log.info("Scheduled retry for scan job {} after startup error", job.getId());
                    } else {
                        log.error("Failed to start scan job {}: {}", job.getId(), result.error());
                    }
                }
                return job;
            }).orElse(null);

            if (result.success() && hasText(result.scanId())
                    && (updatedJob == null
                    || updatedJob.getStatus() != ScanJobStatus.RUNNING
                    || !result.scanId().equals(updatedJob.getZapScanId()))) {
                claimMetrics.recordLateResultCleanups(1);
                if (updatedJob == null) {
                    claimMetrics.recordClaimConflicts(1);
                }
                log.warn(
                        "Cleaning up late scan start result for job {} on worker {} because durable claim ownership moved",
                        result.jobId(),
                        workerNodeId
                );
                stopRequests.add(new StopRequest(result.type(), result.scanId()));
            }
        }

        return new ApplyOutcome(stopRequests);
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
        return true;
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
            case AJAX_SPIDER -> requireAjaxSpiderService().startAjaxSpiderJob(
                    parameters.get(PARAM_TARGET_URL)
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
            case AJAX_SPIDER -> requireAjaxSpiderService().getAjaxSpiderProgressPercent(scanId);
        };
    }

    /**
     * Stop a running scan by delegating to the matching service family.
     */
    private void executeStopRequest(StopRequest stopRequest) {
        switch (stopRequest.type()) {
            case ACTIVE_SCAN, ACTIVE_SCAN_AS_USER -> activeScanService.stopActiveScanJob(stopRequest.scanId());
            case SPIDER_SCAN, SPIDER_SCAN_AS_USER -> spiderScanService.stopSpiderScanJob(stopRequest.scanId());
            case AJAX_SPIDER -> requireAjaxSpiderService().stopAjaxSpiderJob(stopRequest.scanId());
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
    private String formatSubmission(ScanJob job, boolean idempotentReplay) {
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

        appendClaimDetail(sb, job, Instant.now());

        if (hasText(job.getIdempotencyKey())) {
            sb.append('\n').append("Idempotency Key: ").append(job.getIdempotencyKey());
        }

        if (idempotentReplay) {
            sb.append('\n').append("Admission: existing job returned for idempotent retry");
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
        appendClaimDetail(sb, job, Instant.now());
        if (hasText(job.getIdempotencyKey())) {
            sb.append('\n').append("Idempotency Key: ").append(job.getIdempotencyKey());
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

    private AjaxSpiderService requireAjaxSpiderService() {
        if (ajaxSpiderService == null) {
            throw new IllegalStateException("AJAX Spider service is not available in this runtime");
        }
        return ajaxSpiderService;
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

    private void appendClaimSummary(StringBuilder output, ScanJob job, Instant now) {
        if (!hasText(job.getClaimOwnerId())) {
            return;
        }
        output.append(" | claimOwner=").append(job.getClaimOwnerId());
        if (job.getClaimExpiresAt() != null) {
            output.append(" | claimExpiresAt=").append(job.getClaimExpiresAt());
            if (job.hasLiveClaim(now)) {
                output.append(" | claimState=active");
            } else {
                output.append(" | claimState=expired");
            }
        }
    }

    private void appendClaimDetail(StringBuilder output, ScanJob job, Instant now) {
        if (!hasText(job.getClaimOwnerId())) {
            return;
        }
        output.append('\n').append("Claim Owner: ").append(job.getClaimOwnerId());
        if (job.getClaimHeartbeatAt() != null) {
            output.append('\n').append("Claim Heartbeat: ").append(job.getClaimHeartbeatAt());
        }
        if (job.getClaimExpiresAt() != null) {
            output.append('\n').append("Claim Expires: ").append(job.getClaimExpiresAt());
            output.append('\n').append("Claim State: ").append(job.hasLiveClaim(now) ? "ACTIVE" : "EXPIRED");
        }
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

    private String sanitizeWorkerNodeId(String workerNodeId) {
        if (!hasText(workerNodeId)) {
            return "asg-node";
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

    private record WorkPlan(List<PollTarget> pollTargets, List<StartTarget> startTargets) {
    }

    private record ApplyOutcome(List<StopRequest> stopRequests) {
    }

    private record AdmittedJob(ScanJob job, boolean idempotentReplay) {
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

    private static final class ClaimMetrics {
        private final Counter queuedClaimCounter;
        private final Counter runningRecoveryCounter;
        private final Counter expiredClaimRecoveryCounter;
        private final Counter renewedClaimCounter;
        private final Counter claimConflictCounter;
        private final Counter lateResultCleanupCounter;

        private ClaimMetrics(
                Counter queuedClaimCounter,
                Counter runningRecoveryCounter,
                Counter expiredClaimRecoveryCounter,
                Counter renewedClaimCounter,
                Counter claimConflictCounter,
                Counter lateResultCleanupCounter
        ) {
            this.queuedClaimCounter = queuedClaimCounter;
            this.runningRecoveryCounter = runningRecoveryCounter;
            this.expiredClaimRecoveryCounter = expiredClaimRecoveryCounter;
            this.renewedClaimCounter = renewedClaimCounter;
            this.claimConflictCounter = claimConflictCounter;
            this.lateResultCleanupCounter = lateResultCleanupCounter;
        }

        static ClaimMetrics create(MeterRegistry meterRegistry) {
            if (meterRegistry == null) {
                return noop();
            }

            return new ClaimMetrics(
                    counter(meterRegistry, "queued_claimed"),
                    counter(meterRegistry, "running_recovered"),
                    counter(meterRegistry, "expired_claim_recovered"),
                    counter(meterRegistry, "renewed"),
                    counter(meterRegistry, "conflict"),
                    counter(meterRegistry, "late_result_cleanup")
            );
        }

        static ClaimMetrics noop() {
            return new ClaimMetrics(null, null, null, null, null, null);
        }

        void recordQueuedClaims(int count) {
            increment(queuedClaimCounter, count);
        }

        void recordRunningRecoveries(int count) {
            increment(runningRecoveryCounter, count);
        }

        void recordExpiredClaimRecoveries(int count) {
            increment(expiredClaimRecoveryCounter, count);
        }

        void recordRenewedClaims(int count) {
            increment(renewedClaimCounter, count);
        }

        void recordClaimConflicts(int count) {
            increment(claimConflictCounter, count);
        }

        void recordLateResultCleanups(int count) {
            increment(lateResultCleanupCounter, count);
        }

        private static Counter counter(MeterRegistry meterRegistry, String event) {
            return Counter.builder("asg.queue.claim.events")
                    .tag("event", event)
                    .register(meterRegistry);
        }

        private static void increment(Counter counter, int count) {
            if (counter != null && count > 0) {
                counter.increment(count);
            }
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
                        "asg.queue.jobs",
                        List.of(io.micrometer.core.instrument.Tag.of("status", status.name().toLowerCase(Locale.ROOT))),
                        new AtomicInteger(0)
                );
                statusGauges.put(status, gauge);
            }

            AtomicInteger activeClaimsGauge = meterRegistry.gauge(
                    "asg.queue.claims",
                    List.of(io.micrometer.core.instrument.Tag.of("state", "active")),
                    new AtomicInteger(0)
            );
            AtomicInteger expiredClaimsGauge = meterRegistry.gauge(
                    "asg.queue.claims",
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
