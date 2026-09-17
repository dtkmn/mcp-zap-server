package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderStatus;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AjaxSpiderServiceTest {

    private EngineAjaxSpiderExecution ajaxSpiderExecution;
    private UrlValidationService urlValidationService;
    private AjaxSpiderService service;

    @BeforeEach
    void setup() {
        ajaxSpiderExecution = mock(EngineAjaxSpiderExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        service = new AjaxSpiderService(ajaxSpiderExecution, urlValidationService);
    }

    @Test
    void startAjaxSpiderJobValidatesTargetAndDelegatesToGatewayExecution() {
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:1");

        String scanId = service.startAjaxSpiderJob("http://target");

        assertThat(scanId).isEqualTo("ajax-spider:1");
        verify(urlValidationService).validateUrl("http://target");
        verify(ajaxSpiderExecution).startAjaxSpider(new AjaxSpiderScanRequest("http://target"));
    }

    @Test
    void pendingCancellationBlocksDirectAndQueuedStarts() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("cancelling", now);
        job.claim("worker", now, now.plusSeconds(60));
        job.requestCancellation(now, now.plusSeconds(60));
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpider("http://target"))
                .isInstanceOf(EngineBusyException.class)
                .hasMessageContaining("pending cancellation");
        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", job.getId(), ScanJobClaimToken.from(job)))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void runningManagedCrawlBlocksDirectAndQueuedStarts() {
        ScanJob job = ajaxJob("running", Instant.now());
        job.markRunning("ajax-spider:running");
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpider("http://target"))
                .isInstanceOf(EngineBusyException.class);
        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target"))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void unavailableLifecycleLockRejectsStartWithoutCallingEngine() {
        ScanJobStore store = mock(ScanJobStore.class);
        when(store.tryWithAjaxLifecycleLock(any())).thenReturn(Optional.empty());
        service.setScanJobStore(store);

        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target"))
                .isInstanceOf(EngineBusyException.class)
                .hasMessageContaining("already in progress");
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void queuedWorkerMayStartItsClaimedJob() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("claimed", now);
        job.claim("worker", now, now.plusSeconds(60));
        setStoredJobs(job);
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:1");

        assertThat(service.startAjaxSpiderJob("http://target", job.getId(), ScanJobClaimToken.from(job)))
                .isEqualTo("ajax-spider:1");
        verify(ajaxSpiderExecution).startAjaxSpider(new AjaxSpiderScanRequest("http://target"));
    }

    @Test
    void queuedStartRecordsAcceptanceBeforeReleasingLifecycleLock() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("accepted", now);
        job.claim("worker", now, now.plusSeconds(60));
        AtomicBoolean lockHeld = new AtomicBoolean();
        setLockedSnapshot(job, lockHeld);
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:accepted");
        AtomicBoolean accepted = new AtomicBoolean();

        String scanId = service.startAjaxSpiderJob("http://target", job.getId(), ScanJobClaimToken.from(job), id -> {
            assertThat(lockHeld).isTrue();
            assertThat(id).isEqualTo("ajax-spider:accepted");
            verify(ajaxSpiderExecution).startAjaxSpider(new AjaxSpiderScanRequest("http://target"));
            accepted.set(true);
        });

        assertThat(scanId).isEqualTo("ajax-spider:accepted");
        assertThat(accepted).isTrue();
        assertThat(lockHeld).isFalse();
        verify(ajaxSpiderExecution, never()).stopAjaxSpider();
    }

    @Test
    void failedAcceptanceStopsUnderLifecycleLockAndPreservesBothFailures() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("acceptance-failed", now);
        job.claim("worker", now, now.plusSeconds(60));
        AtomicBoolean lockHeld = new AtomicBoolean();
        setLockedSnapshot(job, lockHeld);
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:accepted");
        RuntimeException acceptanceFailure = new IllegalStateException("Unable to persist accepted start");
        RuntimeException stopFailure = new IllegalStateException("ZAP is still initializing");
        doAnswer(invocation -> {
            assertThat(lockHeld).isTrue();
            throw stopFailure;
        }).when(ajaxSpiderExecution).stopAjaxSpider();

        assertThatThrownBy(() -> service.startAjaxSpiderJob(
                "http://target", job.getId(), ScanJobClaimToken.from(job), id -> { throw acceptanceFailure; }))
                .isSameAs(acceptanceFailure);

        assertThat(acceptanceFailure.getSuppressed()).containsExactly(stopFailure);
        assertThat(lockHeld).isFalse();
        verify(ajaxSpiderExecution).stopAjaxSpider();
    }

    @Test
    void rejectedStartDoesNotRecordAcceptanceOrIssueGlobalStop() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("rejected", now);
        job.claim("worker", now, now.plusSeconds(60));
        setStoredJobs(job);
        EngineBusyException rejection = new EngineBusyException("scan_in_progress", null);
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenThrow(rejection);
        AtomicBoolean accepted = new AtomicBoolean();

        assertThatThrownBy(() -> service.startAjaxSpiderJob(
                "http://target", job.getId(), ScanJobClaimToken.from(job), id -> accepted.set(true)))
                .isSameAs(rejection);

        assertThat(accepted).isFalse();
        verify(ajaxSpiderExecution, never()).stopAjaxSpider();
    }

    @Test
    void queuedStartRejectsStaleClaimEvenWhenSameWorkerReclaimsJob() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("reclaimed", now.minusSeconds(60));
        job.claim("worker", now.minusSeconds(60), now.minusSeconds(1));
        ScanJobClaimToken staleClaim = ScanJobClaimToken.from(job);
        job.claim("worker", now, now.plusSeconds(60));
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", job.getId(), staleClaim))
                .isInstanceOf(EngineBusyException.class)
                .hasMessageContaining("rejected before execution");
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void queuedStartRejectsExpiredClaim() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("expired", now.minusSeconds(60));
        job.claim("worker", now.minusSeconds(60), now.minusSeconds(1));
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", job.getId(), ScanJobClaimToken.from(job)))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void queuedStartRechecksCancellationFromLockedSnapshot() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("cancelled-before-lock", now);
        job.claim("worker", now, now.plusSeconds(60));
        ScanJobClaimToken claim = ScanJobClaimToken.from(job);
        ScanJobStore store = mock(ScanJobStore.class);
        when(store.tryWithAjaxLifecycleLock(any())).thenAnswer(invocation -> {
            job.requestCancellation(now, now.plusSeconds(30));
            java.util.function.Function<List<ScanJob>, String> action = invocation.getArgument(0);
            return Optional.of(action.apply(List.of(job)));
        });
        service.setScanJobStore(store);

        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", job.getId(), claim))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void queuedStartDoesNotLaunchAfterJobWasRemovedOrCompleted() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("completed", now);
        job.claim("worker", now, now.plusSeconds(60));
        ScanJobClaimToken claim = ScanJobClaimToken.from(job);
        job.markCancelled();
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", job.getId(), claim))
                .isInstanceOf(EngineBusyException.class);
        assertThatThrownBy(() -> service.startAjaxSpiderJob("http://target", "missing-job", claim))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void directStartCannotOvertakeClaimedQueuedJob() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("claimed", now);
        job.claim("worker", now, now.plusSeconds(60));
        setStoredJobs(job);

        assertThatThrownBy(() -> service.startAjaxSpider("http://target"))
                .isInstanceOf(EngineBusyException.class);
        verifyNoInteractions(ajaxSpiderExecution);
    }

    @Test
    void completedCancellationDoesNotBlockLaterStart() {
        Instant now = Instant.now();
        ScanJob job = ajaxJob("cancelled", now);
        job.requestCancellation(now, now.plusSeconds(60));
        job.markCancelled();
        setStoredJobs(job);
        when(ajaxSpiderExecution.startAjaxSpider(any(AjaxSpiderScanRequest.class))).thenReturn("ajax-spider:2");

        assertThat(service.startAjaxSpider("http://target")).contains("started successfully");
        verify(ajaxSpiderExecution).startAjaxSpider(new AjaxSpiderScanRequest("http://target"));
    }

    private ScanJob ajaxJob(String id, Instant now) {
        return new ScanJob(id, ScanJobType.AJAX_SPIDER, Map.of("url", "http://target"), now, 2);
    }

    private void setStoredJobs(ScanJob... jobs) {
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        store.upsertAll(List.of(jobs));
        service.setScanJobStore(store);
    }

    private void setLockedSnapshot(ScanJob job, AtomicBoolean lockHeld) {
        ScanJobStore store = mock(ScanJobStore.class);
        when(store.tryWithAjaxLifecycleLock(any())).thenAnswer(invocation -> {
            Function<List<ScanJob>, String> action = invocation.getArgument(0);
            lockHeld.set(true);
            try {
                return Optional.of(action.apply(List.of(job)));
            } finally {
                lockHeld.set(false);
            }
        });
        service.setScanJobStore(store);
    }

    @Test
    void getAjaxSpiderStatusFormatsGatewayStatus() {
        when(ajaxSpiderExecution.readAjaxSpiderStatus()).thenReturn(new AjaxSpiderStatus("running", "3", true));

        String result = service.getAjaxSpiderStatus();

        assertThat(result)
                .contains("AJAX Spider Status: running")
                .contains("Pages/URLs discovered: 3")
                .contains("Scan is in progress...");
        assertThat(service.isAjaxSpiderRunning()).isTrue();
    }

    @Test
    void stoppedCrawlDoesNotClaimSuccessfulCompletion() {
        when(ajaxSpiderExecution.readAjaxSpiderStatus()).thenReturn(new AjaxSpiderStatus("stopped", "2", false));

        service.stopAjaxSpider();
        String result = service.getAjaxSpiderStatus();

        assertThat(result)
                .contains("AJAX Spider Status: stopped")
                .contains("Pages/URLs discovered: 2")
                .contains("ZAP does not distinguish completion from cancellation or failure")
                .doesNotContain("Scan completed", "100%");
        assertThat(service.isAjaxSpiderRunning()).isFalse();
        verify(ajaxSpiderExecution).stopAjaxSpider();
    }
}
