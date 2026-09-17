package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineAjaxSpiderExecution.AjaxSpiderStatus;
import mcp.server.zap.core.gateway.EngineBusyException;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

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
@Slf4j
@Service
public class AjaxSpiderService {
    private final EngineAjaxSpiderExecution ajaxSpiderExecution;
    private final UrlValidationService urlValidationService;
    private OperationRegistry operationRegistry;
    private ClientWorkspaceResolver clientWorkspaceResolver;
    private ScanHistoryLedgerService scanHistoryLedgerService;
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
    void setClientWorkspaceResolver(ClientWorkspaceResolver clientWorkspaceResolver) {
        this.clientWorkspaceResolver = clientWorkspaceResolver;
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    @Autowired(required = false)
    void setScanJobStore(ScanJobStore scanJobStore) {
        this.scanJobStore = scanJobStore;
    }

    /**
     * Start an AJAX Spider scan against the given URL using a real browser.
     * This is more effective against JavaScript-heavy sites and WAF protection.
     *
     * @param targetUrl Target URL to scan with AJAX Spider
     * @return Status message with scan information
     */
    public String startAjaxSpider(String targetUrl) {
        String scanId = startAjaxSpider(targetUrl, null, null);
        trackAjaxSpiderStarted(scanId, resolveWorkspaceId());
        recordDirectScanStarted("ajax_spider", scanId, targetUrl);
        log.info("AJAX Spider direct scan id {}", scanId);
        return formatDirectStartMessage(targetUrl);
    }

    /**
     * Launch without a queued ownership claim and return a synthetic scan id.
     */
    public String startAjaxSpiderJob(String targetUrl) {
        return startAjaxSpider(targetUrl, null, null);
    }

    /** Launch a queued crawl only while its original durable claim still owns the job. */
    public String startAjaxSpiderJob(String targetUrl, String jobId, ScanJobClaimToken claimToken) {
        if (jobId == null || jobId.isBlank() || claimToken == null) {
            throw new IllegalArgumentException("A queued AJAX Spider start requires its job and claim");
        }
        if (scanJobStore == null) {
            throw new IllegalStateException("A queued AJAX Spider start requires the scan job store");
        }
        return startAjaxSpider(targetUrl, jobId, claimToken);
    }

    private String startAjaxSpider(String targetUrl, String jobId, ScanJobClaimToken claimToken) {
        // Validate URL before scanning
        urlValidationService.validateUrl(targetUrl);

        if (scanJobStore == null) {
            return ajaxSpiderExecution.startAjaxSpider(new AjaxSpiderScanRequest(targetUrl));
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
                            || (!Objects.equals(jobId, job.getId())
                            && job.getStatus() == ScanJobStatus.QUEUED && job.hasLiveClaim(now)));
            if (occupied) {
                throw new EngineBusyException("AJAX Spider has an active job or pending cancellation", null);
            }
            return ajaxSpiderExecution.startAjaxSpider(new AjaxSpiderScanRequest(targetUrl));
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

    private String formatDirectStartMessage(String targetUrl) {
        return String.format(
                "AJAX Spider scan started successfully for URL: %s%n" +
                        "Using real browser (Firefox headless) to crawl JavaScript content.%n" +
                        "This method bypasses WAF protection and bot detection.%n" +
                        "Using default scan duration and browser settings.%n" +
                        "Use 'zap_ajax_spider_status' to check progress.",
                targetUrl
        );
    }

    private void trackAjaxSpiderStarted(String scanId, String workspaceId) {
        if (operationRegistry == null || scanId == null || scanId.isBlank()) {
            return;
        }
        operationRegistry.registerDirectScan("ajax:" + scanId, workspaceId);
    }

    private void recordDirectScanStarted(String operationKind, String scanId, String targetUrl) {
        if (scanHistoryLedgerService == null) {
            return;
        }
        scanHistoryLedgerService.recordDirectScanStarted(operationKind, scanId, targetUrl, Map.of());
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

    private String resolveWorkspaceId() {
        if (clientWorkspaceResolver == null) {
            return "default-workspace";
        }
        return clientWorkspaceResolver.resolveCurrentWorkspaceId();
    }

}
