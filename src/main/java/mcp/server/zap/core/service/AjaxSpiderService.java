package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.NativeScanLaunch;

import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderStatus;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.OperationRegistry;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Service for managing ZAP AJAX Spider Scans.
 * <p>
 * The AJAX Spider uses a real browser (Firefox/Chrome via Selenium) to crawl JavaScript-heavy
 * applications and websites that block traditional HTTP-based spiders. This is particularly
 * useful for:
 * - Single Page Applications (SPAs) built with React, Angular, Vue
 * - Sites with WAF protection that block automated tools
 * - Sites that require JavaScript to render content
 * - Sites with bot detection mechanisms
 */
@Service
public class AjaxSpiderService {
    private final EngineAjaxSpiderExecution ajaxSpiderExecution;
    private final UrlValidationService urlValidationService;
    private OperationRegistry operationRegistry;
    private ScanJobStore scanJobStore;

    /**
     * Build-time dependency injection constructor.
     */
    public AjaxSpiderService(EngineAjaxSpiderExecution ajaxSpiderExecution,
                            UrlValidationService urlValidationService) {
        this.ajaxSpiderExecution = ajaxSpiderExecution;
        this.urlValidationService = urlValidationService;
    }

    @Autowired(required = false)
    void setOperationRegistry(OperationRegistry operationRegistry) {
        this.operationRegistry = operationRegistry;
    }

    @Autowired(required = false)
    void setScanJobStore(ScanJobStore scanJobStore) {
        this.scanJobStore = scanJobStore;
    }

    /**
     * Launch without a queued ownership claim and return a synthetic scan id.
     */
    public String startAjaxSpiderJob(String targetUrl) {
        return startAjaxSpider(targetUrl, null, null);
    }

    /** Launch a queued crawl only while its original durable claim still owns the job. */
    public String startAjaxSpiderJob(String targetUrl, String jobId, ScanJobClaimToken claimToken) {
        return startAjaxSpiderJob(targetUrl, jobId, claimToken, scanId -> { });
    }

    /** Record accepted queued starts before another managed AJAX lifecycle operation can begin. */
    public String startAjaxSpiderJob(String targetUrl, String jobId, ScanJobClaimToken claimToken,
                                    Consumer<String> onAccepted) {
        if (jobId == null || jobId.isBlank() || claimToken == null) {
            throw new IllegalArgumentException("A queued AJAX Spider start requires its job and claim");
        }
        if (scanJobStore == null) {
            throw new IllegalStateException("A queued AJAX Spider start requires the scan job store");
        }
        return startAjaxSpider(targetUrl, jobId, claimToken, Objects.requireNonNull(onAccepted));
    }

    private String launchAjaxSpider(String targetUrl, NativeScanLaunch launch) {
        AjaxSpiderScanRequest request = new AjaxSpiderScanRequest(targetUrl);
        return launch == NativeScanLaunch.UNTRACKED ? ajaxSpiderExecution.startAjaxSpider(request)
                : ajaxSpiderExecution.startAjaxSpider(request, launch);
    }

    private String startAjaxSpider(String targetUrl, String jobId, ScanJobClaimToken claimToken) {
        return startAjaxSpider(targetUrl, jobId, claimToken, scanId -> { });
    }

    public String startAjaxSpiderJobTracked(String targetUrl, String jobId, ScanJobClaimToken claimToken,
                                    NativeScanLaunch launch) {
        if (jobId == null || jobId.isBlank() || claimToken == null || scanJobStore == null) {
            throw new IllegalArgumentException("A queued AJAX launch requires its durable job and claim");
        }
        return startAjaxSpider(targetUrl, jobId, claimToken, scanId -> { }, launch);
    }

    private String startAjaxSpider(String targetUrl, String jobId, ScanJobClaimToken claimToken,
                                   Consumer<String> onAccepted) {
        return startAjaxSpider(targetUrl, jobId, claimToken, onAccepted, NativeScanLaunch.UNTRACKED);
    }

    private String startAjaxSpider(String targetUrl, String jobId, ScanJobClaimToken claimToken,
                                   Consumer<String> onAccepted, NativeScanLaunch launch) {
        // Validate URL before scanning
        urlValidationService.validateUrl(targetUrl);

        if (scanJobStore == null) {
            return launchAjaxSpider(targetUrl, launch);
        }
        // ZAP exposes one global AJAX crawler. Serialize starts with managed stop retries.
        return scanJobStore.tryWithAjaxLifecycleLock(jobs -> {
            Instant now = Instant.now();
            if (jobId != null) {
                ScanJob claimed = jobs.stream().filter(job -> jobId.equals(job.getId())).findFirst().orElse(null);
                if (claimed == null || claimed.getType() != ScanJobType.AJAX_SPIDER
                        || claimed.getStatus() != ScanJobStatus.QUEUED || claimed.isCancellationRequested()
                        || !claimed.hasLiveClaim(now) || !claimToken.matches(claimed)) {
                    throw new EngineBusyException("AJAX Spider start rejected before execution: "
                            + "queued ownership changed or cancellation was requested", null);
                }
            }
            boolean occupied = jobs.stream()
                    .filter(job -> job.getType() == ScanJobType.AJAX_SPIDER)
                    .filter(job -> !job.getStatus().isTerminal())
                    .anyMatch(job -> job.isCancellationRequested()
                            || job.getStatus() == ScanJobStatus.RUNNING
                            || job.hasUnresolvedLaunch()
                            || (!Objects.equals(jobId, job.getId())
                            && job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)));
            if (occupied) {
                throw new EngineBusyException("AJAX Spider has an active job or pending cancellation", null);
            }
            String scanId = launchAjaxSpider(targetUrl, launch);
            try {
                onAccepted.accept(scanId);
            } catch (RuntimeException | Error failure) {
                // The crawl started, but its owner could not record acceptance. Stop while the
                // lifecycle lock still guarantees that this cannot target a newer managed crawl.
                try {
                    ajaxSpiderExecution.stopAjaxSpider();
                } catch (RuntimeException | Error stopFailure) {
                    if (stopFailure != failure) {
                        failure.addSuppressed(stopFailure);
                    }
                }
                throw failure;
            }
            return scanId;
        }).orElseThrow(() -> new EngineBusyException("AJAX Spider lifecycle operation is already in progress", null));
    }

    /**
     * Get the status of the AJAX Spider scan.
     *
     * @return Current status of AJAX Spider scan
     */
    public String getAjaxSpiderStatus() {
        AjaxSpiderStatus ajaxStatus = ajaxSpiderExecution.readAjaxSpiderStatus();
        trackAjaxSpiderStatus(ajaxStatus.running());

        boolean isRunning = ajaxStatus.running();

        return String.format(
            "AJAX Spider Status: %s%n" +
            "Pages/URLs discovered: %s%n" +
            "%s",
            ajaxStatus.status(),
            ajaxStatus.discoveredCount(),
            isRunning ? "Scan is in progress..."
                    : "Scan is not running. ZAP does not distinguish completion from cancellation or failure."
        );
    }

    /**
     * Stop the currently running AJAX Spider scan.
     *
     * @return Confirmation message
     */
    public String stopAjaxSpider() {
        stopAjaxSpiderJob();
        trackAjaxSpiderStopped();
        return "AJAX Spider scan stopped successfully";
    }

    public void stopAjaxSpiderJob() {
        ajaxSpiderExecution.stopAjaxSpider();
    }

    /**
     * Get the full results from the AJAX Spider scan.
     *
     * @return Full results including all discovered URLs
     */
    public String getAjaxSpiderResults() {
        return "AJAX Spider Results:\n" + ajaxSpiderExecution.loadAjaxSpiderResults();
    }

    public boolean isAjaxSpiderRunning() {
        return ajaxSpiderExecution.readAjaxSpiderStatus().running();
    }

    private void trackAjaxSpiderStatus(boolean running) {
        if (operationRegistry == null) {
            return;
        }
        if (running) {
            operationRegistry.touchDirectScansByPrefix("ajax:");
            return;
        }
        operationRegistry.releaseDirectScansByPrefix("ajax:");
    }

    private void trackAjaxSpiderStopped() {
        if (operationRegistry == null) {
            return;
        }
        operationRegistry.releaseDirectScansByPrefix("ajax:");
    }

}
