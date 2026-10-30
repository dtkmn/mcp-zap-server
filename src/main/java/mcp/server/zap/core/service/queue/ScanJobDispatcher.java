package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.gateway.EngineBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ScanJobDispatcher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ScanJobDispatcher.class);
    private static final AtomicInteger PLATFORM_THREAD_COUNTER = new AtomicInteger(0);
    private static final Duration DEFAULT_TARGET_TIMEOUT = Duration.ofSeconds(10);

    private final ScanJobRuntimeExecutor runtimeExecutor;
    private final ExecutorService ioExecutor;
    private final Duration targetTimeout;
    private ScanJobLaunchSafety launchSafety;

    public void setLaunchSafety(ScanJobLaunchSafety launchSafety) {
        if (this.launchSafety != null) throw new IllegalStateException("Launch safety is already configured");
        this.launchSafety = Objects.requireNonNull(launchSafety);
    }

    public ScanJobDispatcher(ScanJobRuntimeExecutor runtimeExecutor, ExecutorService ioExecutor) {
        this(runtimeExecutor, ioExecutor, DEFAULT_TARGET_TIMEOUT);
    }

    ScanJobDispatcher(ScanJobRuntimeExecutor runtimeExecutor, ExecutorService ioExecutor, Duration targetTimeout) {
        this.runtimeExecutor = Objects.requireNonNull(runtimeExecutor);
        this.ioExecutor = Objects.requireNonNull(ioExecutor);
        this.targetTimeout = requirePositiveTimeout(targetTimeout);
    }

    public static ScanJobDispatcher create(ScanJobRuntimeExecutor runtimeExecutor, boolean virtualThreadsEnabled) {
        if (virtualThreadsEnabled) {
            log.info("Scan queue IO dispatcher initialized with virtual threads");
            return new ScanJobDispatcher(runtimeExecutor, Executors.newVirtualThreadPerTaskExecutor());
        }
        ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread t = new Thread(task);
            t.setDaemon(true);
            t.setName("scan-queue-io-" + PLATFORM_THREAD_COUNTER.incrementAndGet());
            return t;
        });
        log.info("Scan queue IO dispatcher initialized with cached platform thread pool");
        return new ScanJobDispatcher(runtimeExecutor, executor);
    }

    private ScanJobLaunchSafety requireLaunchSafety() {
        return Objects.requireNonNull(launchSafety, "Queued execution requires configured launch safety");
    }

    public ScanJobDispatchResult dispatch(ScanJobWorkPlan workPlan) {
        requireLaunchSafety();
        CompletionService<IndexedDispatchResult> completionService = new ExecutorCompletionService<>(ioExecutor);
        List<SubmittedDispatch> submittedDispatches = new ArrayList<>(
                workPlan.pollTargets().size() + workPlan.startTargets().size()
        );
        List<ScanJobPollResult> pollResults = new ArrayList<>(
                Collections.nCopies(workPlan.pollTargets().size(), null)
        );
        List<ScanJobStartResult> startResults = new ArrayList<>(
                Collections.nCopies(workPlan.startTargets().size(), null)
        );

        submitPollTargets(workPlan.pollTargets(), completionService, submittedDispatches);
        submitStartTargets(workPlan.startTargets(), completionService, submittedDispatches);
        collectCompletedResults(
                completionService,
                submittedDispatches,
                pollResults,
                startResults
        );
        fillMissingResults(workPlan, pollResults, startResults);

        return new ScanJobDispatchResult(pollResults, startResults);
    }

    public void executeStopRequest(ScanJobStopRequest stopRequest) {
        requireLaunchSafety().executeOwnedStop(stopRequest, () -> {
            runtimeExecutor.stopScan(stopRequest.type(), stopRequest.scanId());
            if (runtimeExecutor.readProgress(stopRequest.type(), stopRequest.scanId()) < 100) {
                throw new IllegalStateException("Stop request returned, but native termination is not yet confirmed");
            }
        });
    }

    @Override
    public void close() {
        ioExecutor.shutdownNow();
    }

    private void submitPollTargets(
            List<ScanJobPollTarget> pollTargets,
            CompletionService<IndexedDispatchResult> completionService,
            List<SubmittedDispatch> submittedDispatches
    ) {
        for (int i = 0; i < pollTargets.size(); i++) {
            int index = i;
            ScanJobPollTarget target = pollTargets.get(i);
            Future<IndexedDispatchResult> future = completionService.submit(() ->
                    IndexedDispatchResult.poll(index, executePollTarget(target))
            );
            submittedDispatches.add(SubmittedDispatch.poll(index, future));
        }
    }

    private void submitStartTargets(
            List<ScanJobStartTarget> startTargets,
            CompletionService<IndexedDispatchResult> completionService,
            List<SubmittedDispatch> submittedDispatches
    ) {
        for (int i = 0; i < startTargets.size(); i++) {
            int index = i;
            ScanJobStartTarget target = startTargets.get(i);
            ScanJobLaunchSafety.StartAttempt attempt = requireLaunchSafety().newAttempt(target);
            Future<IndexedDispatchResult> future = completionService.submit(() ->
                    IndexedDispatchResult.start(index, executeStartTarget(target, attempt))
            );
            submittedDispatches.add(SubmittedDispatch.start(index, future, attempt));
        }
    }

    private void collectCompletedResults(
            CompletionService<IndexedDispatchResult> completionService,
            List<SubmittedDispatch> submittedDispatches,
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults
    ) {
        long deadlineNanos = System.nanoTime() + targetTimeout.toNanos();
        int completed = 0;
        while (completed < submittedDispatches.size()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            try {
                Future<IndexedDispatchResult> completedFuture = completionService.poll(
                        remainingNanos,
                        TimeUnit.NANOSECONDS
                );
                if (completedFuture == null) {
                    break;
                }

                applyCompletedResult(awaitFuture(completedFuture), pollResults, startResults);
                completed += 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        drainCompletedResults(completionService, pollResults, startResults);
        for (SubmittedDispatch submittedDispatch : submittedDispatches) {
            if (hasResult(submittedDispatch, pollResults, startResults)) {
                continue;
            }
            if (submittedDispatch.future().isDone()) {
                applyCompletedResult(awaitFuture(submittedDispatch.future()), pollResults, startResults);
            }
        }

        int timedOut = 0;
        for (SubmittedDispatch submittedDispatch : submittedDispatches) {
            if (hasResult(submittedDispatch, pollResults, startResults)) {
                continue;
            }
            if (submittedDispatch.future().isDone()) {
                applyCompletedResult(awaitFuture(submittedDispatch.future()), pollResults, startResults);
                continue;
            }
            submittedDispatch.markTimedOut();
            if (submittedDispatch.future().cancel(true)) {
                timedOut += 1;
            }
        }
        if (timedOut > 0) {
            log.warn(
                    "Timed out {} scan queue dispatch task(s) after {} ms",
                    timedOut,
                    targetTimeout.toMillis()
            );
        }
    }

    private void drainCompletedResults(
            CompletionService<IndexedDispatchResult> completionService,
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults
    ) {
        Future<IndexedDispatchResult> completedFuture = completionService.poll();
        while (completedFuture != null) {
            applyCompletedResult(awaitFuture(completedFuture), pollResults, startResults);
            completedFuture = completionService.poll();
        }
    }

    private void applyCompletedResult(
            IndexedDispatchResult result,
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults
    ) {
        if (result != null && result.pollResult() != null) {
            pollResults.set(result.index(), result.pollResult());
        } else if (result != null && result.startResult() != null) {
            startResults.set(result.index(), result.startResult());
        }
    }

    private boolean hasResult(
            SubmittedDispatch submittedDispatch,
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults
    ) {
        if (submittedDispatch.kind() == DispatchKind.POLL) {
            return pollResults.get(submittedDispatch.index()) != null;
        }
        return startResults.get(submittedDispatch.index()) != null;
    }

    private void fillMissingResults(
            ScanJobWorkPlan workPlan,
            List<ScanJobPollResult> pollResults,
            List<ScanJobStartResult> startResults
    ) {
        for (int i = 0; i < pollResults.size(); i++) {
            if (pollResults.get(i) == null) {
                pollResults.set(i, ScanJobPollResult.failure(
                        workPlan.pollTargets().get(i),
                        "Runtime status check failed: dispatch timed out"
                ));
            }
        }
        for (int i = 0; i < startResults.size(); i++) {
            if (startResults.get(i) == null) {
                startResults.set(i, ScanJobStartResult.failure(
                        workPlan.startTargets().get(i),
                        "Startup failed: dispatch timed out"
                ));
            }
        }
    }

    private ScanJobPollResult executePollTarget(ScanJobPollTarget target) {
        try {
            int progress = runtimeExecutor.readProgress(target.type(), target.scanId());
            return ScanJobPollResult.success(target, progress);
        } catch (Exception e) {
            return ScanJobPollResult.failure(target, "Runtime status check failed: " + e.getMessage());
        }
    }

    private ScanJobStartResult executeStartTarget(ScanJobStartTarget target, ScanJobLaunchSafety.StartAttempt attempt) {
        try {
            String scanId = runtimeExecutor.startScan(target, attempt);
            return ScanJobStartResult.success(target, scanId);
        } catch (EngineBusyException e) {
            attempt.rejected();
            return ScanJobStartResult.busy(target, e.getMessage());
        } catch (Exception e) {
            attempt.failed();
            return ScanJobStartResult.failure(target, "Startup failed: " + e.getMessage());
        } finally {
            attempt.finished();
        }
    }

    private IndexedDispatchResult awaitFuture(Future<IndexedDispatchResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private Duration requirePositiveTimeout(Duration timeout) {
        Duration candidate = Objects.requireNonNull(timeout, "targetTimeout must not be null");
        if (candidate.isZero() || candidate.isNegative()) {
            throw new IllegalArgumentException("targetTimeout must be positive");
        }
        return candidate;
    }

    private record IndexedDispatchResult(
            int index,
            ScanJobPollResult pollResult,
            ScanJobStartResult startResult
    ) {
        static IndexedDispatchResult poll(int index, ScanJobPollResult result) {
            return new IndexedDispatchResult(index, result, null);
        }

        static IndexedDispatchResult start(int index, ScanJobStartResult result) {
            return new IndexedDispatchResult(index, null, result);
        }
    }

    private enum DispatchKind {
        POLL,
        START
    }

    private record SubmittedDispatch(
            DispatchKind kind,
            int index,
            Future<IndexedDispatchResult> future,
            ScanJobLaunchSafety.StartAttempt attempt
    ) {
        static SubmittedDispatch poll(int index, Future<IndexedDispatchResult> future) {
            return new SubmittedDispatch(DispatchKind.POLL, index, future, null);
        }

        static SubmittedDispatch start(
                int index,
                Future<IndexedDispatchResult> future,
                ScanJobLaunchSafety.StartAttempt attempt
        ) {
            return new SubmittedDispatch(DispatchKind.START, index, future, attempt);
        }

        void markTimedOut() {
            if (attempt != null) {
                attempt.timedOut();
            }
        }
    }

}
