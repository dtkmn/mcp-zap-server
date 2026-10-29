package mcp.server.zap.core.service.queue;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Write-ahead launch ownership. A renewable claim never substitutes for an external-effect receipt. */
@Service
@EnableConfigurationProperties(ScanLaunchSafetyProperties.class)
public class ScanJobLaunchSafety implements AutoCloseable {
    private final ScanJobStore store;
    private final ScanLaunchJournal journal;
    private final String controllerId = UUID.randomUUID().toString();
    private String workerId = controllerId;
    private long claimLeaseMs = 15000L;
    private final java.util.concurrent.ConcurrentMap<String, StartAttempt> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> legacyAtStartup = new java.util.HashSet<>();
    private boolean recovered;

    public ScanJobLaunchSafety(ScanJobStore store, ScanLaunchSafetyProperties properties,
                              @Value("${zap.report.directory:/zap/wrk}") String reportRoot,
                              @Value("${zap.automation.local-directory:/zap/wrk/automation}") String automationRoot) {
        this.store = store;
        journal = store instanceof InMemoryScanJobStore
                ? new ScanLaunchJournal(Path.of(properties.getJournalDirectory()),
                    java.util.List.of(Path.of(reportRoot), Path.of(automationRoot))) : null;
    }

    /** Recover safety holds, not queued history, from a memory-store controller's durable journal. */
    public synchronized void recover() {
        if (recovered) return;
        if (journal != null) {
            for (ScanLaunchJournal.Receipt receipt : journal.orphanedReceipts()) {
                ScanJob job = new ScanJob(receipt.jobId(), receipt.type(), Map.of(),
                        receipt.attempt().dispatchedAt(), 0, receipt.requesterId(), null);
                job.restoreLaunchAttempt(receipt.attempt());
                if (receipt.scanId() != null) job.markRunning(receipt.scanId());
                job.markLaunchUnknown("Controller restarted with an outstanding native launch; operator reconciliation required");
                store.upsertAll(java.util.List.of(job));
            }
        }
        fenceOrphanedReceipts(true);
        recovered = true;
    }

    public synchronized void configureWorker(String workerId, long claimLeaseMs) {
        if (recovered) throw new IllegalStateException("Launch ownership must be configured before recovery");
        this.workerId = Objects.requireNonNull(workerId);
        this.claimLeaseMs = claimLeaseMs;
    }

    public synchronized void refreshHolds() {
        recover();
        fenceOrphanedReceipts(false);
    }

    private void fenceOrphanedReceipts(boolean initialRecovery) {
        Instant now = Instant.now();
        store.updateAndGet(jobs -> {
            for (ScanJob job : jobs) {
                if (initialRecovery && job.getLaunchAttempt() == null && !job.getStatus().isTerminal()
                        && (job.getStatus() == ScanJobStatus.RUNNING
                        || (job.getStatus() == ScanJobStatus.QUEUED && job.getClaimOwnerId() != null))) {
                    legacyAtStartup.add(job.getId());
                }
                if (job.getStatus().isTerminal() || liveSiblingOwns(job, now)) continue;
                if (legacyAtStartup.contains(job.getId()) && job.getLaunchAttempt() == null
                        && (job.getStatus() == ScanJobStatus.RUNNING
                        || (job.getStatus() == ScanJobStatus.QUEUED && job.getClaimOwnerId() != null))) {
                    job.restoreLaunchAttempt(new ScanJob.LaunchAttempt(UUID.randomUUID().toString(),
                            "legacy-unverified", job.getCreatedAt(), 0));
                    job.markLaunchUnknown("Legacy job may have dispatched without a launch receipt; operator reconciliation required");
                    record(job);
                }
                if (job.getLaunchAttempt() != null || job.getClaimOwnerId() == null) legacyAtStartup.remove(job.getId());
                ScanJob.LaunchAttempt attempt = job.getLaunchAttempt();
                if (attempt != null && !controllerId.equals(attempt.controllerId())
                        && job.getStatus() != ScanJobStatus.START_OUTCOME_UNKNOWN) {
                    job.markLaunchUnknown("Launch owner is no longer live; operator reconciliation is required before using its native ID");
                    record(job);
                }
            }
            return jobs;
        });
    }

    private boolean liveSiblingOwns(ScanJob job, Instant now) {
        return job.hasLiveClaim(now) && !workerId.equals(job.getClaimOwnerId());
    }

    public synchronized void requireAdmissionSafe() {
        recover();
        if (journal != null) journal.requireNoOrphans();
    }

    public synchronized boolean admissionFenced() {
        recover();
        return journal != null && journal.admissionFenced();
    }

    public StartAttempt newAttempt(ScanJobStartTarget target) {
        StartAttempt start = new StartAttempt(target, new ScanJob.LaunchAttempt(UUID.randomUUID().toString(), controllerId,
                Instant.now(), 0));
        inFlight.put(start.attempt.id(), start);
        return start;
    }

    private synchronized void begin(StartAttempt start) {
        requireAdmissionSafe();
        if (start.timedOut.get()) throw new IllegalStateException("Launch dispatch expired before its native effect");
        ScanJob saved = store.updateClaimedJob(start.target.jobId(), start.target.claimToken(), Instant.now(), job -> {
            job.beginLaunch(start.attempt);
            record(job);
            return job;
        }).orElseThrow(() -> new IllegalStateException("Launch intent was not committed; native start refused"));
        if (!start.attempt.equals(saved.getLaunchAttempt()) || saved.getStatus() != ScanJobStatus.STARTING) {
            throw new IllegalStateException("Native launch requires its committed start receipt");
        }
        start.dispatched.set(true);
        if (start.timedOut.get()) {
            unknown(start, "Dispatch expired after intent persistence; no automatic relaunch");
            throw new IllegalStateException("Native launch dispatch expired");
        }
    }

    private synchronized void accept(StartAttempt start, String scanId) {
        if (scanId == null || !scanId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("A native scan receipt requires a bounded scan ID");
        }
        if (!start.dispatched.get()) throw new IllegalStateException("Native scan returned without a persisted launch intent");
        store.updateLaunchAttempt(start.target.jobId(), start.attempt.id(), start.attempt.resolutionVersion(), job -> {
            if (job.getStatus().isTerminal()) throw new IllegalStateException("A resolved launch cannot accept a late receipt");
            if (job.getZapScanId() != null && !scanId.equals(job.getZapScanId())) {
                throw new IllegalStateException("Launch already has a different native receipt");
            }
            job.markRunning(scanId);
            Instant now = Instant.now();
            job.claim(workerId, now, now.plusMillis(claimLeaseMs));
            record(job);
            return job;
        }).orElseThrow(() -> new IllegalStateException("Launch ownership changed; late native receipt was not adopted"));
    }

    private synchronized void unknown(StartAttempt start, String reason) {
        if (!start.dispatched.get()) return;
        store.updateLaunchAttempt(start.target.jobId(), start.attempt.id(), start.attempt.resolutionVersion(), job -> {
            // An accepted receipt remains pollable even if its caller never received a reply.
            if (job.hasUnresolvedLaunch()) {
                job.markLaunchUnknown(reason);
                record(job);
            }
            return job;
        });
    }

    private synchronized void rejected(StartAttempt start) {
        if (!start.dispatched.get()) return;
        store.updateLaunchAttempt(start.target.jobId(), start.attempt.id(), start.attempt.resolutionVersion(), job -> {
            if (!job.hasUnresolvedLaunch() || job.getZapScanId() != null) throw new IllegalStateException("Launch rejection has stale ownership");
            job.markRejectedLaunch();
            if (job.isCancellationRequested()) job.markCancelled();
            else if (start.timedOut.get()) job.markWaitingForEngine(Instant.now(), Instant.now().plusSeconds(1), "ZAP explicitly rejected the late native start as busy");
            if (journal != null) journal.remove(job.getId(), start.attempt.id());
            return job;
        }).orElseThrow(() -> new IllegalStateException("Launch rejection could not release its exact receipt"));
    }

    private void record(ScanJob job) {
        if (journal != null) journal.record(new ScanLaunchJournal.Receipt(job.getId(), job.getType(),
                job.getRequesterId(), Objects.requireNonNull(job.getLaunchAttempt()), job.getZapScanId()));
    }

    public synchronized void clearTerminalReceipts() {
        if (journal == null) return;
        for (ScanJob job : store.list()) {
            if (job.getStatus().isTerminal() && job.getLaunchAttempt() != null) {
                journal.remove(job.getId(), job.getLaunchAttempt().id());
            }
        }
    }

    /** The caller must separately enforce operator authority and the existing workspace boundary. */
    public synchronized ScanJob reconcile(String jobId, String attemptId, long expectedVersion,
                                         String resolution, String nativeScanId, boolean confirmOwnership) {
        if (!confirmOwnership) throw new IllegalArgumentException("Explicit operator ownership/quiescence attestation is required");
        if (!"attach_scan".equals(resolution) && !"confirm_stopped".equals(resolution)) {
            throw new IllegalArgumentException("resolution must be attach_scan or confirm_stopped");
        }
        if (inFlight.containsKey(attemptId)) {
            throw new IllegalStateException("The originating controller still has an in-flight start; it cannot be retired or reassigned");
        }
        return store.updateLaunchAttempt(jobId, attemptId, expectedVersion, job -> {
            if (!job.hasUnresolvedLaunch() && !("confirm_stopped".equals(resolution)
                    && job.getStatus() == ScanJobStatus.RUNNING)) {
                throw new IllegalStateException("attach_scan requires a held launch; confirm_stopped requires held or running receipt ownership");
            }
            ScanJob.LaunchAttempt resolved = job.getLaunchAttempt().resolvedBy(controllerId);
            if ("attach_scan".equals(resolution)) {
                if (job.getType() == mcp.server.zap.core.model.ScanJobType.AJAX_SPIDER) {
                    throw new IllegalArgumentException("AJAX has a global crawler and cannot attach an independently owned native ID");
                }
                if (nativeScanId == null || !nativeScanId.matches("[A-Za-z0-9_.:-]{1,128}")) {
                    throw new IllegalArgumentException("attach_scan requires a bounded nativeScanId");
                }
                for (ScanJob other : store.listStrict()) {
                    if (!other.getId().equals(jobId) && !other.getStatus().isTerminal()
                            && sameNativeNamespace(job, other) && nativeScanId.equals(other.getZapScanId())) {
                        throw new IllegalStateException("Native scan ID is already owned by another nonterminal job");
                    }
                }
                job.restoreLaunchAttempt(resolved);
                job.markRunning(nativeScanId);
                job.clearClaim();
                Instant now = Instant.now();
                job.claim(workerId, now, now.plusMillis(claimLeaseMs));
                if (journal != null) journal.resolved(new ScanLaunchJournal.Receipt(job.getId(), job.getType(),
                        job.getRequesterId(), resolved, nativeScanId));
            } else {
                job.restoreLaunchAttempt(resolved);
                job.markCancelled();
                job.recordTransientError("Trusted operator confirmed this launch has stopped or its engine was replaced; no scan success is asserted");
                if (journal != null) journal.remove(jobId, attemptId);
            }
            return job;
        }).orElseThrow(() -> new IllegalStateException("Launch attempt or reconciliation version changed; refresh status"));
    }

    private boolean sameNativeNamespace(ScanJob left, ScanJob right) {
        if (left.getType().isActiveFamily()) return right.getType().isActiveFamily();
        boolean leftHttp = left.getType() == mcp.server.zap.core.model.ScanJobType.SPIDER_SCAN
                || left.getType() == mcp.server.zap.core.model.ScanJobType.SPIDER_SCAN_AS_USER;
        boolean rightHttp = right.getType() == mcp.server.zap.core.model.ScanJobType.SPIDER_SCAN
                || right.getType() == mcp.server.zap.core.model.ScanJobType.SPIDER_SCAN_AS_USER;
        return leftHttp ? rightHttp : left.getType() == right.getType();
    }

    /** Serialize a bounded stop with operator resolution using the same durable attempt/version lock. */
    public void executeOwnedStop(ScanJobStopRequest request, Runnable stopAndConfirm) {
        if (request.jobId() == null || request.launchAttemptId() == null) {
            throw new IllegalStateException("Native stop requires exact durable launch ownership");
        }
        store.updateLaunchAttempt(request.jobId(), request.launchAttemptId(), request.resolutionVersion(), job -> {
            if (job.getStatus() != ScanJobStatus.RUNNING || job.getType() != request.type()
                    || !Objects.equals(job.getZapScanId(), request.scanId()) || !job.isCancellationRequested()) {
                throw new IllegalStateException("Native stop ownership is held, stale or not cancellation-authorized");
            }
            // The store holds its transaction/row lock until this bounded native action returns.
            // Reconciliation cannot retire/reassign this receipt between its check and external effect.
            stopAndConfirm.run();
            return job;
        }).orElseThrow(() -> new IllegalStateException("Native stop receipt changed before dispatch"));
    }

    public final class StartAttempt implements NativeScanLaunch {
        private final ScanJobStartTarget target;
        private final ScanJob.LaunchAttempt attempt;
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicBoolean dispatched = new AtomicBoolean();

        private StartAttempt(ScanJobStartTarget target, ScanJob.LaunchAttempt attempt) {
            this.target = target;
            this.attempt = attempt;
        }

        @Override public void beforeDispatch() { begin(this); }
        @Override public void accepted(String scanId) { persistCompletion(() -> accept(this, scanId)); }
        public boolean dispatched() { return dispatched.get(); }
        public void rejected() { persistCompletion(() -> ScanJobLaunchSafety.this.rejected(this)); }
        public void failed() { persistCompletion(() -> unknown(this, "Native start outcome is unknown; automatic relaunch is disabled")); }
        public void timedOut() {
            timedOut.set(true);
            unknown(this, "Native dispatch timed out; possible execution remains held for reconciliation");
            if (!dispatched.get()) finished();
        }
        public void finished() { inFlight.remove(attempt.id(), this); }

        private void persistCompletion(Runnable completion) {
            // Future cancellation may interrupt an HTTP request that nevertheless returns a receipt.
            // Preserve that evidence before restoring the interrupt; never clear it before dispatch.
            boolean interrupted = Thread.interrupted();
            try { completion.run(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    @Override
    @PreDestroy
    public void close() throws Exception {
        if (journal != null) journal.close();
    }
}
