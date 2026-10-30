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
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import mcp.server.zap.core.service.queue.ScanJobClaimMetrics;
import mcp.server.zap.core.service.queue.ScanJobLaunchSafety;
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
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private Duration engineBusyMaxWait = Duration.ofMinutes(3);
    private Duration cancelMaxWait = Duration.ofSeconds(30);
    private ScanJobClaimManager claimManager;
    private ScanJobResultApplier resultApplier;
    private QueueStateMetrics queueStateMetrics = QueueStateMetrics.noop();
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ScanJobAccessBoundary scanJobAccessBoundary;
    private ScanJobLaunchSafety launchSafety;

    void configureLaunchSafety(ScanJobLaunchSafety safety) {
        if (launchSafety != null) throw new IllegalStateException("Launch safety is already configured");
        launchSafety = Objects.requireNonNull(safety);
        dispatcher.setLaunchSafety(safety);
        safety.configureWorker(workerNodeId, claimLeaseMs);
        safety.recover();
        restoreStateFromStore();
    }

    public ScanJob requireReconciliationVisibleJob(String jobId) {
        String normalized = requireText(jobId, "jobId");
        return requireVisibleJob(normalized, scanJobStore.load(normalized).orElse(null));
    }

    public String reconciliationWorkspaceId() { return resolveCurrentWorkspaceId(); }

    public String getReconciliationStatus(String jobId) {
        List<ScanJob> visible = hasText(jobId)
                ? List.of(requireReconciliationVisibleJob(jobId))
                : scanJobStore.listStrict().stream().filter(this::canCurrentRequesterAccess)
                    .filter(ScanJob::hasUnresolvedLaunch).limit(100).toList();
        StringBuilder result = new StringBuilder("Launch reconciliation status (operator recovery; no native calls):\n");
        for (ScanJob job : visible) {
            result.append("Job ID: ").append(job.getId()).append("\nType: ").append(job.getType())
                    .append("\nStatus: ").append(job.getStatus()).append('\n');
            if (job.getLaunchAttempt() != null) {
                result.append("Launch Attempt: ").append(job.getLaunchAttempt().id())
                        .append("\nReconciliation Version: ").append(job.getLaunchAttempt().resolutionVersion())
                        .append("\nOrigin Controller: ").append(job.getLaunchAttempt().controllerId()).append('\n');
            }
            if (job.getZapScanId() != null) result.append("Recorded Native Scan ID: ").append(job.getZapScanId()).append('\n');
            if (job.hasUnresolvedLaunch()) result.append("Possible execution remains held; no automatic relaunch or unverified stop.\n");
        }
        if (visible.isEmpty()) result.append("No visible held launch receipts.\n");
        if (visible.size() == 100) result.append("Listing limited to 100 jobs; inspect a known job ID individually.\n");
        return result.toString();
    }

    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final Deque<String> queuedJobIds = new ArrayDeque<>();
    private final ReentrantLock queueLock = new ReentrantLock();

    @Autowired
    public ScanJobQueueService(ActiveScanService activeScanService,
                               SpiderScanService spiderScanService,
                               ObjectProvider<AjaxSpiderService> ajaxSpiderServiceProvider,
                               ObjectProvider<ClientSpiderService> clientSpiderServiceProvider,
                               UrlValidationService urlValidationService,
                               ScanLimitProperties scanLimitProperties,
                               ObjectProvider<ScanJobStore> scanJobStoreProvider,
                               ObjectProvider<QueueLeadershipCoordinator> queueLeadershipCoordinatorProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider,
                               ScanJobLaunchSafety launchSafety,
                               @Value("${zap.scan.queue.virtual-threads.enabled:false}") boolean virtualThreadsEnabled,
                               @Value("${zap.scan.queue.claim-lease-ms:15000}") long claimLeaseMs,
                               @Value("${zap.scan.queue.retry.active.max-attempts:3}") int activeMaxAttempts,
                               @Value("${zap.scan.queue.retry.active.initial-backoff-ms:2000}") long activeInitialBackoffMs,
                               @Value("${zap.scan.queue.retry.active.max-backoff-ms:30000}") long activeMaxBackoffMs,
                               @Value("${zap.scan.queue.retry.active.multiplier:2.0}") double activeBackoffMultiplier,
                               @Value("${zap.scan.queue.retry.spider.max-attempts:2}") int spiderMaxAttempts,
                               @Value("${zap.scan.queue.retry.spider.initial-backoff-ms:1000}") long spiderInitialBackoffMs,
                               @Value("${zap.scan.queue.retry.spider.max-backoff-ms:10000}") long spiderMaxBackoffMs,
                               @Value("${zap.scan.queue.retry.spider.multiplier:2.0}") double spiderBackoffMultiplier,
                               @Value("${zap.scan.queue.engine-busy-max-wait-ms:180000}") long engineBusyMaxWaitMs,
                               @Value("${zap.scan.queue.cancel-max-wait-ms:${zap.scan.queue.ajax-cancel-max-wait-ms:30000}}") long cancelMaxWaitMs) {
        this(
                activeScanService,
                spiderScanService,
                ajaxSpiderServiceProvider.getIfAvailable(),
                clientSpiderServiceProvider.getIfAvailable(),
                urlValidationService,
                scanLimitProperties,
                new RetryPolicy(activeMaxAttempts, activeInitialBackoffMs, activeMaxBackoffMs, activeBackoffMultiplier),
                new RetryPolicy(spiderMaxAttempts, spiderInitialBackoffMs, spiderMaxBackoffMs, spiderBackoffMultiplier),
                virtualThreadsEnabled,
                scanJobStoreProvider.getIfAvailable(InMemoryScanJobStore::new),
                queueLeadershipCoordinatorProvider.getIfAvailable(SingleNodeQueueLeadershipCoordinator::new)
        );
        this.claimLeaseMs = sanitizeClaimLeaseMs(claimLeaseMs);
        if (engineBusyMaxWaitMs < 0) {
            throw new IllegalArgumentException("zap.scan.queue.engine-busy-max-wait-ms must not be negative");
        }
        this.engineBusyMaxWait = Duration.ofMillis(engineBusyMaxWaitMs);
        if (cancelMaxWaitMs <= 0) {
            throw new IllegalArgumentException("zap.scan.queue.cancel-max-wait-ms must be positive");
        }
        this.cancelMaxWait = Duration.ofMillis(cancelMaxWaitMs);
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
                this.spiderRetryPolicy,
                this.engineBusyMaxWait
        );
        this.queueStateMetrics = QueueStateMetrics.create(meterRegistry);
        configureLaunchSafety(launchSafety);
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
        this(activeScanService, spiderScanService, ajaxSpiderService, null,
                urlValidationService, scanLimitProperties, activeRetryPolicy, spiderRetryPolicy,
                virtualThreadsEnabled, scanJobStore, queueLeadershipCoordinator);
    }

    ScanJobQueueService(ActiveScanService activeScanService,
                        SpiderScanService spiderScanService,
                        AjaxSpiderService ajaxSpiderService,
                        ClientSpiderService clientSpiderService,
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
                ajaxSpiderService,
                clientSpiderService
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

    public String queueClientSpiderScan(String targetUrl, Integer maxDepth, String idempotencyKey) {
        return queueClientSpiderScan(targetUrl, maxDepth, null, null, idempotencyKey);
    }

    public String queueClientSpiderScan(String targetUrl, Integer maxDepth, String contextName,
                                        String userName, String idempotencyKey) {
        if (maxDepth != null && maxDepth < 0) {
            throw new IllegalArgumentException("Client Spider maxDepth must not be negative");
        }
        String normalizedContextName = normalizeBlankToNull(contextName);
        String normalizedUserName = normalizeBlankToNull(userName);
        if ((normalizedContextName == null) != (normalizedUserName == null)) {
            throw new IllegalArgumentException("Client Spider contextName and userName must be supplied together");
        }
        Map<String, String> parameters = targetParameters(targetUrl);
        if (maxDepth != null) {
            parameters.put(ScanJobParameterNames.MAX_DEPTH, maxDepth.toString());
        }
        if (normalizedContextName != null && normalizedUserName != null) {
            parameters.put(ScanJobParameterNames.CONTEXT_NAME, normalizedContextName);
            parameters.put(ScanJobParameterNames.USER_NAME, normalizedUserName);
        }
        return submitQueuedScan(ScanJobType.CLIENT_SPIDER, parameters, idempotencyKey);
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
        String detail = responseFormatter.formatJobDetail(job, job.getQueuePosition(), Instant.now());
        List<String> cleanupIds = filterVisibleJobs(scanJobStore.list()).stream()
                .filter(candidate -> normalizedJobId.equals(candidate.getCleanupOfJobId()) && !candidate.getStatus().isTerminal())
                .map(ScanJob::getId).toList();
        return cleanupIds.isEmpty() ? detail : detail + "\nUnresolved cleanup jobs: " + String.join(", ", cleanupIds);
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
        String[] response = new String[1];
        boolean[] durableCancellation = new boolean[1];

        List<ScanJob> committedJobs = updateQueueState(currentState -> {
            ScanJob job = currentState.jobs().get(normalizedJobId);
            job = requireVisibleJob(normalizedJobId, job);

            Instant now = Instant.now();
            if (job.hasUnresolvedLaunch()
                    || (job.getStatus() == ScanJobStatus.RUNNING && hasText(job.getZapScanId()))
                    || ((job.getType() == ScanJobType.AJAX_SPIDER || job.isCleanupJob())
                    && (job.getStatus() == ScanJobStatus.RUNNING
                    || (job.getStatus() == ScanJobStatus.QUEUED
                    && (job.hasLiveClaim(now) || job.isCancellationRequested()))))) {
                job.requestCancellation(now, now.plus(cancelMaxWait));
                durableCancellation[0] = true;
                return currentState;
            }

            if (job.getStatus() == ScanJobStatus.QUEUED) {
                currentState.queuedJobIds().remove(normalizedJobId);
                job.markCancelled();
                response[0] = "Scan job cancelled: " + normalizedJobId + " (was QUEUED)";
                return currentState;
            }

            if (job.getStatus() == ScanJobStatus.RUNNING) {
                job.markCancelled();
                response[0] = "Scan job cancelled: " + normalizedJobId + " (was RUNNING)";
                return currentState;
            }

            response[0] = "Scan job " + normalizedJobId + " is already terminal with status " + job.getStatus() + ".";
            return currentState;
        });
        applyCommittedStoredJobs(committedJobs);

        processQueue();
        if (durableCancellation[0]) {
            ScanJob job = requireVisibleJob(normalizedJobId, scanJobStore.load(normalizedJobId).orElse(null));
            return responseFormatter.formatJobDetail(job, job.getQueuePosition(), Instant.now());
        }
        return response[0];
    }

    /**
     * Dispatch queued work, refresh running progress, and persist resulting state.
     */
    @Scheduled(fixedDelayString = "${zap.scan.queue.dispatch-interval-ms:2000}")
    public void processQueue() {
        requireLaunchSafety();
        observeLeadership();
        launchSafety.refreshHolds();
        restoreStateFromStore(false);
        Instant now = Instant.now();
        processAjaxCancellations(now);
        processNativeCancellations(Instant.now());
        now = Instant.now();
        expireEngineBusyWaits(now);
        Instant claimUntil = now.plusMillis(claimLeaseMs);
        claimManager.renewInFlightClaims(now, claimUntil);

        boolean admissionFenced = launchSafety.admissionFenced();
        ScanJobWorkPlan workPlan = claimManager.claimWork(
                snapshotJobsForClaimObservation(),
                admissionFenced ? 0 : scanLimitProperties.getMaxConcurrentActiveScans(),
                admissionFenced ? 0 : scanLimitProperties.getMaxConcurrentSpiderScans(),
                now,
                claimUntil
        );
        if (workPlan.pollTargets().isEmpty() && workPlan.startTargets().isEmpty()) {
            applyCommittedStoredJobs(scanJobStore.list());
            launchSafety.clearTerminalReceipts();
            return;
        }

        ScanJobDispatchResult dispatchResult = dispatcher.dispatch(workPlan);
        ScanJobApplyOutcome applyOutcome = resultApplier.applyResults(
                dispatchResult.pollResults(),
                dispatchResult.startResults(),
                claimUntil
        );
        if (applyOutcome.persistenceFailure() != null) {
            throw applyOutcome.persistenceFailure();
        }
        applyCommittedStoredJobs(scanJobStore.list());
        launchSafety.clearTerminalReceipts();
    }

    private ScanJobLaunchSafety requireLaunchSafety() {
        return Objects.requireNonNull(launchSafety, "Queued execution requires configured launch safety");
    }

    private void processNativeCancellations(Instant now) {
        if (snapshotJobsForClaimObservation().stream().noneMatch(job ->
                job.getType() != ScanJobType.AJAX_SPIDER && job.isCancellationPending())) {
            return;
        }
        List<ScanJob> claimed = scanJobStore.claimRunningJobs(workerNodeId, now, now.plusMillis(claimLeaseMs));
        for (ScanJob candidate : claimManager.copyJobs(claimed)) {
            if (candidate.getType() == ScanJobType.AJAX_SPIDER || !candidate.isCancellationPending()) {
                continue;
            }
            ScanJobClaimToken token = ScanJobClaimToken.from(candidate);
            ScanJob[] reserved = new ScanJob[1];
            Instant attemptAt = Instant.now();
            Optional<ScanJob> committed;
            try {
                committed = scanJobStore.updateClaimedJob(candidate.getId(), token, attemptAt, job -> {
                    reserved[0] = null;
                    if (!sameCancellation(candidate, job) || !job.isCancellationPending()) {
                        return job;
                    }
                    if (!attemptAt.isBefore(job.getCancelDeadlineAt())) {
                        job.recordCancellationFailure(cancellationUnconfirmed());
                        return job;
                    }
                    if (attemptAt.isBefore(job.getCancelNextAttemptAt())) {
                        return job;
                    }
                    // Reserve this attempt before IO. Other workers can recover it after the lease,
                    // while unrelated scan IDs need no shared AJAX lifecycle lock.
                    job.deferCancellationAttempt(attemptAt.plusMillis(claimLeaseMs).truncatedTo(ChronoUnit.MICROS));
                    reserved[0] = claimManager.copyJobs(List.of(job)).iterator().next();
                    return job;
                });
            } catch (RuntimeException failure) {
                Instant failedAt = Instant.now();
                if (!failedAt.isBefore(candidate.getCancelNextAttemptAt()) && failedAt.isBefore(candidate.getCancelDeadlineAt())) {
                    log.warn("Native stop withheld after ownership persistence failure; launch receipt remains held");
                }
                throw failure;
            }
            if (committed.isEmpty() || reserved[0] == null) {
                continue;
            }
            ScanJob expected = reserved[0];
            RuntimeException stopFailure = null;
            try {
                dispatcher.executeStopRequest(ScanJobStopRequest.owned(expected));
            } catch (RuntimeException e) {
                stopFailure = e;
            }
            Instant finishedAt = Instant.now();
            RuntimeException failure = stopFailure;
            scanJobStore.updateClaimedJob(expected.getId(), token, finishedAt, job -> {
                if (!sameCancellation(expected, job)
                        || !Objects.equals(expected.getCancelNextAttemptAt(), job.getCancelNextAttemptAt())) {
                    return job;
                }
                applyCancellationResult(job, failure, finishedAt);
                if (failure != null) {
                    job.clearClaim();
                }
                return job;
            });
        }
        applyCommittedStoredJobs(scanJobStore.list());
    }

    private void processAjaxCancellations(Instant now) {
        List<ScanJob> pending = snapshotJobsForClaimObservation().stream()
                .filter(job -> job.getType() == ScanJobType.AJAX_SPIDER && job.isCancellationPending())
                .filter(job -> !job.hasUnresolvedLaunch())
                .toList();
        for (ScanJob candidate : pending) {
            if (!now.isBefore(candidate.getCancelDeadlineAt())) {
                updateAjaxCancellation(candidate, job -> job.recordCancellationFailure(cancellationUnconfirmed()));
                continue;
            }
            if (now.isBefore(candidate.getCancelNextAttemptAt())) {
                continue;
            }
            // The same gate protects direct and queued AJAX starts on every worker. Keep it
            // until the stop call returns: a timed-out future must never become a late global stop.
            scanJobStore.tryWithAjaxLifecycleLock(currentJobs -> {
                ScanJob current = currentJobs.stream()
                        .filter(job -> candidate.getId().equals(job.getId()))
                        .findFirst().orElse(null);
                Instant attemptAt = Instant.now();
                if (!sameCancellation(candidate, current) || !current.isCancellationPending()
                        || attemptAt.isBefore(current.getCancelNextAttemptAt())
                        || !attemptAt.isBefore(current.getCancelDeadlineAt())
                        || !hasText(current.getZapScanId())
                        || (current.getStatus() == ScanJobStatus.QUEUED && current.hasLiveClaim(attemptAt))) {
                    return false;
                }
                boolean conflictingOwner = currentJobs.stream()
                        .filter(job -> !job.getId().equals(current.getId()))
                        .filter(job -> job.getType() == ScanJobType.AJAX_SPIDER && !job.getStatus().isTerminal())
                        .anyMatch(job -> job.getStatus() == ScanJobStatus.RUNNING
                                || job.hasUnresolvedLaunch()
                                || job.isCancellationRequested() || job.hasLiveClaim(attemptAt));
                if (conflictingOwner) {
                    return false;
                }
                ScanJob expected = claimManager.copyJobs(List.of(current)).iterator().next();
                RuntimeException stopFailure = null;
                try {
                    dispatcher.executeStopRequest(ScanJobStopRequest.owned(current));
                } catch (RuntimeException e) {
                    stopFailure = e;
                }
                Instant finishedAt = Instant.now();
                RuntimeException failure = stopFailure;
                updateAjaxCancellation(expected, job -> applyCancellationResult(job, failure, finishedAt));
                return true;
            });
        }
    }

    private void applyCancellationResult(ScanJob job, RuntimeException failure, Instant finishedAt) {
        if (failure == null) {
            job.markCancelled();
            return;
        }
        Instant retryAt = finishedAt.plusMillis(policyFor(job.getType()).computeDelayMs(job.getCancelAttemptCount() + 1));
        boolean expired = !finishedAt.isBefore(job.getCancelDeadlineAt());
        Instant next = expired ? null : (retryAt.isAfter(job.getCancelDeadlineAt()) ? job.getCancelDeadlineAt() : retryAt);
        job.scheduleCancellationRetry(next, expired ? cancellationUnconfirmed()
                : "Cancellation pending; ZAP stop request failed: " + failure.getMessage());
    }

    private void updateAjaxCancellation(ScanJob expected, java.util.function.Consumer<ScanJob> update) {
        List<ScanJob> committed = updateQueueState(state -> {
            ScanJob current = state.jobs().get(expected.getId());
            if (sameCancellation(expected, current)) {
                update.accept(current);
                if (current.getStatus().isTerminal()) {
                    state.queuedJobIds().remove(current.getId());
                }
            }
            return state;
        });
        applyCommittedStoredJobs(committed);
    }

    private boolean sameCancellation(ScanJob expected, ScanJob current) {
        return current != null && current.isCancellationRequested()
                && Objects.equals(expected.getCancelRequestedAt(), current.getCancelRequestedAt())
                && Objects.equals(expected.getCancelDeadlineAt(), current.getCancelDeadlineAt())
                && Objects.equals(expected.getZapScanId(), current.getZapScanId())
                && Objects.equals(expected.getLaunchAttempt(), current.getLaunchAttempt());
    }

    private String cancellationUnconfirmed() {
        return "Unable to confirm cancellation; the scan may still be running. "
                + "Automatic stop retries ended. Request cancellation again to retry.";
    }

    ScanJob getJobForTesting(String jobId) {
        queueLock.lock();
        try {
            return jobs.get(jobId);
        } finally {
            queueLock.unlock();
        }
    }

    private void expireEngineBusyWaits(Instant now) {
        if (snapshotJobsForClaimObservation().stream().noneMatch(job -> busyWaitExpired(job, now))) {
            return;
        }
        List<ScanJob> committedJobs = updateQueueState(state -> {
            for (ScanJob job : state.jobs().values()) {
                if (busyWaitExpired(job, now)) {
                    job.markFailed("Engine busy wait timed out after " + engineBusyMaxWait.toMillis()
                            + " ms: " + job.getLastError());
                    state.queuedJobIds().remove(job.getId());
                }
            }
            return state;
        });
        applyCommittedStoredJobs(committedJobs);
    }

    private boolean busyWaitExpired(ScanJob job, Instant now) {
        return job.getStatus() == ScanJobStatus.QUEUED
                && !job.isCancellationRequested()
                && job.getBusyWaitStartedAt() != null
                && !job.hasLiveClaim(now)
                && !now.isBefore(job.getBusyWaitStartedAt().plus(engineBusyMaxWait));
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
        int repairedRunningJobs = 0;
        if (normalizedState.normalized()) {
            // The initial read only detects repair work. Re-read under the store's
            // transaction lock so repair cannot overwrite a peer's accepted start or claim.
            int[] repairCount = new int[1];
            List<ScanJob> committedJobs = updateQueueState(currentState -> {
                repairCount[0] = currentState.repairedRunningJobs();
                return currentState;
            });
            normalizedState = queueStateNormalizer.normalize(committedJobs);
            repairedRunningJobs = repairCount[0];
        }

        queueLock.lock();
        try {
            applyNormalizedStateLocked(normalizedState, Instant.now());
        } finally {
            queueLock.unlock();
        }

        if (repairedRunningJobs > 0) {
            log.warn("Repaired {} restored RUNNING jobs missing ZAP scan IDs", repairedRunningJobs);
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
        requireLaunchSafety();
        AdmittedJob admission = enqueueJob(type, parameters, idempotencyKey);
        return responseFormatter.formatSubmission(admission.job(), admission.idempotentReplay(), Instant.now());
    }

    private List<ScanJob> filterVisibleJobs(List<ScanJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        return jobs.stream()
                .filter(this::canCurrentRequesterAccess)
                .toList();
    }

    private ScanJob requireVisibleJob(String jobId, ScanJob job) {
        if (!canCurrentRequesterAccess(job)) {
            throw new IllegalArgumentException("No scan job found for ID: " + jobId);
        }
        return job;
    }

    private boolean canCurrentRequesterAccess(ScanJob job) {
        if (job == null) {
            return false;
        }
        if (scanJobAccessBoundary != null) {
            return scanJobAccessBoundary.canCurrentRequesterAccess(job);
        }
        String currentWorkspaceId = resolveCurrentWorkspaceId();
        String jobWorkspaceId = resolveWorkspaceId(job.getRequesterId());
        return currentWorkspaceId.equals(jobWorkspaceId);
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

    private void applyNormalizedStateLocked(ScanJobQueueState state, Instant now) {
        jobs.clear();
        jobs.putAll(state.jobs());
        queuedJobIds.clear();
        queuedJobIds.addAll(state.queuedJobIds());
        queueStateMetrics.refresh(state.jobs().values(), now);

        // This view can be older than live dispatch work. Only dispatch results
        // release in-flight IDs; refreshing durable state must not discard them.
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
            applyNormalizedStateLocked(queueStateNormalizer.normalize(committedJobs), Instant.now());
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

    private String resolveCurrentWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return resolveWorkspaceId(resolveRequesterId());
        }
        return normalizeWorkspaceId(clientWorkspaceResolver.resolveCurrentWorkspaceId());
    }

    private String resolveWorkspaceId(String requesterId) {
        if (clientWorkspaceResolver == null) {
            return normalizeWorkspaceId(requesterId);
        }
        return normalizeWorkspaceId(clientWorkspaceResolver.resolveWorkspaceId(requesterId));
    }

    private String normalizeWorkspaceId(String value) {
        if (!hasText(value)) {
            return DEFAULT_REQUESTER_ID;
        }
        return value.trim();
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
