package mcp.server.zap.core.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineAdapter;
import mcp.server.zap.core.gateway.EngineCapability;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.gateway.UnsupportedEngineCapabilityException;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.service.auth.bootstrap.AuthBootstrapKind;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.auth.bootstrap.HttpOrigin;
import mcp.server.zap.core.service.auth.bootstrap.PreparedAuthSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuidedSecurityToolsServiceTest {

    private GuidedExecutionModeResolver executionModeResolver;
    private SpiderScanService spiderScanService;
    private AjaxSpiderService ajaxSpiderService;
    private ClientSpiderService clientSpiderService;
    private ActiveScanService activeScanService;
    private ScanJobQueueService scanJobQueueService;
    private GuidedAuthSessionService guidedAuthSessionService;
    private ReportService reportService;
    private FindingsService findingsService;
    private OpenApiService openApiService;
    private EngineAdapter engineAdapter;
    private GatewayRecordFactory gatewayRecordFactory;
    private GuidedScanWorkflowService guidedScanWorkflowService;
    private GuidedSecurityToolsService service;

    @BeforeEach
    void setUp() {
        executionModeResolver = mock(GuidedExecutionModeResolver.class);
        spiderScanService = mock(SpiderScanService.class);
        ajaxSpiderService = mock(AjaxSpiderService.class);
        clientSpiderService = mock(ClientSpiderService.class);
        activeScanService = mock(ActiveScanService.class);
        scanJobQueueService = mock(ScanJobQueueService.class);
        guidedAuthSessionService = mock(GuidedAuthSessionService.class);
        reportService = mock(ReportService.class);
        findingsService = mock(FindingsService.class);
        openApiService = mock(OpenApiService.class);
        engineAdapter = new ZapEngineAdapter();
        gatewayRecordFactory = new GatewayRecordFactory();
        guidedScanWorkflowService = new GuidedScanWorkflowService(
                executionModeResolver,
                spiderScanService,
                ajaxSpiderService,
                clientSpiderService,
                activeScanService,
                scanJobQueueService,
                guidedAuthSessionService,
                engineAdapter,
                gatewayRecordFactory
        );
        service = new GuidedSecurityToolsService(
                guidedScanWorkflowService,
                reportService,
                findingsService,
                openApiService,
                engineAdapter,
                gatewayRecordFactory
        );
    }

    @Test
    void startCrawlUsesDirectSpiderWhenQueueNotPreferred() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(spiderScanService.startSpiderScan(eq("https://example.com")))
                .thenReturn("""
                        Direct spider scan started.
                        Scan ID: spider-1
                        Target URL: https://example.com
                        Use 'zap_spider_status' to monitor progress and 'zap_passive_scan_wait' before reading findings.
                        For durable retries, queue visibility, or HA-safe execution, prefer 'zap_queue_spider_scan'.
                        """);
        when(spiderScanService.getSpiderScanStatus("spider-1"))
                .thenReturn("""
                        Direct spider scan status:
                        Scan ID: spider-1
                        Progress: 45%
                        Completed: no
                        Use 'zap_spider_stop' to stop this direct crawl, or 'zap_queue_spider_scan' for durable queued execution next time.
                        """);

        String startResponse = service.startCrawl("https://example.com", "auto", null, null);
        String operationId = extractOperationId(startResponse);
        String statusResponse = service.getCrawlStatus(operationId);

        assertThat(startResponse).contains("Guided crawl started.");
        assertThat(startResponse).contains("Execution Mode: direct");
        assertThat(startResponse).contains("Strategy: http");
        assertThat(startResponse).contains("Next Actions:");
        assertThat(startResponse).contains("Poll: call zap_crawl_status");
        assertThat(startResponse).contains("When crawl is complete: call zap_attack_start");
        assertThat(startResponse).doesNotContain("zap_spider_status");
        assertThat(startResponse).doesNotContain("zap_queue_spider_scan");
        assertThat(statusResponse).contains("Guided crawl status.");
        assertThat(statusResponse).contains("Progress: 45%");
        assertThat(statusResponse).contains("Continue: call zap_crawl_status");
        assertThat(statusResponse).doesNotContain("zap_spider_stop");
        assertThat(statusResponse).doesNotContain("zap_queue_spider_scan");
    }

    @Test
    void startCrawlFallsBackToAjaxWhenAutoStrategyFailsInDirectMode() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(spiderScanService.startSpiderScan(eq("https://spa.example.com")))
                .thenThrow(new ZapApiException("regular spider blocked", new RuntimeException("blocked")));
        when(ajaxSpiderService.startAjaxSpider(eq("https://spa.example.com")))
                .thenReturn("AJAX Spider scan started successfully for URL: https://spa.example.com");

        String response = service.startCrawl("https://spa.example.com", "auto", null, null);

        assertThat(response)
            .contains("Guided crawl started.")
            .contains("Strategy: browser")
            .contains("Auto strategy fell back");
    }

    @Test
    void clientCrawlUsesItsNativeIdForDirectStatusAndStop() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(clientSpiderService.startClientSpider("https://spa.example.com", null))
                .thenReturn("Client Spider started.\nScan ID: 7");
        when(clientSpiderService.getClientSpiderStatus("7")).thenReturn("Progress: 35%");
        when(clientSpiderService.stopClientSpider("7")).thenReturn("Client Spider stop requested.");

        String response = service.startCrawl("https://spa.example.com", "client", null, null);
        String operationId = extractOperationId(response);

        assertThat(response).contains("Strategy: client", "Execution Mode: direct");
        assertThat(service.getCrawlStatus(operationId)).contains("Progress: 35%");
        assertThat(service.stopCrawl(operationId)).contains("Client Spider stop requested.");
        verify(clientSpiderService).getClientSpiderStatus("7");
        verify(clientSpiderService).stopClientSpider("7");
        verifyNoInteractions(spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void clientCrawlUsesQueueJobForStatusAndCancellation() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueClientSpiderScan("https://spa.example.com", null, "crawl-key"))
                .thenReturn("Scan job accepted\nJob ID: client-job\nType: CLIENT_SPIDER");
        when(scanJobQueueService.getScanJobStatus("client-job")).thenReturn("Status: RUNNING");
        when(scanJobQueueService.cancelScanJob("client-job")).thenReturn("Status: CANCELLED");

        String response = service.startCrawl("https://spa.example.com", "client", "crawl-key", null);
        String operationId = extractOperationId(response);

        assertThat(response).contains("Strategy: client", "Execution Mode: queue");
        assertThat(service.getCrawlStatus(operationId)).contains("Status: RUNNING");
        assertThat(service.stopCrawl(operationId)).contains("Status: CANCELLED");
        verify(scanJobQueueService).cancelScanJob("client-job");
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService);
    }

    @Test
    void stoppedClientCrawlsDoNotClaimSuccessfulCoverageInEitherExecutionMode() {
        when(clientSpiderService.startClientSpider("https://spa.example.com", null))
                .thenReturn("Client Spider started.\nScan ID: 7");
        when(clientSpiderService.getClientSpiderStatus("7")).thenReturn("Progress: 100%");
        when(scanJobQueueService.queueClientSpiderScan("https://spa.example.com", null, null))
                .thenReturn("Job ID: client-job");
        when(scanJobQueueService.getScanJobStatus("client-job"))
                .thenReturn("Status: SUCCEEDED (ZAP reports stopped; crawl outcome unknown)");

        for (GuidedExecutionModeResolver.ExecutionMode mode : GuidedExecutionModeResolver.ExecutionMode.values()) {
            when(executionModeResolver.resolveDefaultMode()).thenReturn(mode);
            String operationId = extractOperationId(service.startCrawl("https://spa.example.com", "client", null, null));

            assertThat(service.getCrawlStatus(operationId))
                    .contains("successful completion is unconfirmed", "zap_passive_scan_wait")
                    .doesNotContain("Continue security testing: call zap_attack_start", "Continue: call zap_crawl_status");
        }
    }

    @Test
    void startAttackUsesQueueWhenQueuePreferred() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueActiveScan(eq("https://example.com"), eq("true"), eq("Baseline"), eq((String) null)))
                .thenReturn("""
                        Scan job accepted
                        Job ID: job-7
                        Type: ACTIVE_SCAN
                        """);
        when(scanJobQueueService.getScanJobStatus("job-7"))
                .thenReturn("Scan job details\nJob ID: job-7\nStatus: RUNNING");

        String startResponse = service.startAttack("https://example.com", "true", "Baseline", null, null);
        String operationId = extractOperationId(startResponse);
        String statusResponse = service.getAttackStatus(operationId);

        assertThat(startResponse).contains("Guided attack started.")
            .contains("Execution Mode: queue")
            .contains("Next Actions:")
            .contains("Poll: call zap_attack_status")
            .contains("When attack is complete: call zap_passive_scan_wait");
        assertThat(statusResponse).contains("Guided attack status.")
            .contains("Job ID: job-7")
            .contains("Continue: call zap_attack_status");
    }

    @Test
    void completedCrawlStatusPointsToAttackOrPassiveWait() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(spiderScanService.startSpiderScan(eq("https://example.com")))
                .thenReturn("""
                        Direct spider scan started.
                        Scan ID: spider-100
                        Target URL: https://example.com
                        """);
        when(spiderScanService.getSpiderScanStatus("spider-100"))
                .thenReturn("""
                        Direct spider scan status:
                        Scan ID: spider-100
                        Progress: 100%
                        Completed: yes
                        """);

        String operationId = extractOperationId(service.startCrawl("https://example.com", "http", null, null));
        String statusResponse = service.getCrawlStatus(operationId);

        assertThat(statusResponse).contains("Guided crawl status.");
        assertThat(statusResponse).contains("Next Actions:");
        assertThat(statusResponse).contains("Continue security testing: call zap_attack_start");
        assertThat(statusResponse).contains("Crawl-only path: call zap_passive_scan_wait");
    }

    @Test
    void stoppedDirectBrowserCrawlDoesNotClaimSuccessOrKeepPolling() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(ajaxSpiderService.startAjaxSpider("https://spa.example.com"))
                .thenReturn("AJAX Spider scan started successfully for URL: https://spa.example.com");
        when(ajaxSpiderService.getAjaxSpiderStatus())
                .thenReturn("AJAX Spider Status: stopped\nPages/URLs discovered: 3");

        String operationId = extractOperationId(service.startCrawl("https://spa.example.com", "browser", null, null));
        String statusResponse = service.getCrawlStatus(operationId);

        assertThat(statusResponse)
                .contains("AJAX Spider Status: stopped")
                .contains("successful completion is unconfirmed")
                .contains("zap_passive_scan_wait before reviewing available findings")
                .doesNotContain("Continue security testing: call zap_attack_start", "Continue: call zap_crawl_status");
    }

    @Test
    void succeededBrowserQueueJobDoesNotImplySuccessfulCrawl() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueAjaxSpiderScan("https://spa.example.com", null))
                .thenReturn("Scan job accepted\nJob ID: browser-1\nType: AJAX_SPIDER");
        when(scanJobQueueService.getScanJobStatus("browser-1"))
                .thenReturn("Scan job details\nJob ID: browser-1\nStatus: SUCCEEDED (ZAP reports stopped; crawl outcome unknown)");

        String operationId = extractOperationId(service.startCrawl("https://spa.example.com", "browser", null, null));
        String statusResponse = service.getCrawlStatus(operationId);

        assertThat(statusResponse)
                .contains("successful completion is unconfirmed")
                .contains("check crawl coverage before continuing security testing")
                .doesNotContain("Continue security testing: call zap_attack_start", "Continue: call zap_crawl_status");
    }

    @Test
    void completedAttackStatusPointsToPassiveWaitAndFindings() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(activeScanService.startActiveScan(eq("https://example.com"), eq("true"), eq((String) null)))
                .thenReturn("""
                        Active scan started.
                        Scan ID: active-100
                        Target URL: https://example.com
                        """);
        when(activeScanService.getActiveScanStatus("active-100"))
                .thenReturn("""
                        Direct active scan status:
                        Scan ID: active-100
                        Progress: 100%
                        Completed: yes
                        """);

        String operationId = extractOperationId(service.startAttack("https://example.com", "true", null, null, null));
        String statusResponse = service.getAttackStatus(operationId);

        assertThat(statusResponse).contains("Guided attack status.");
        assertThat(statusResponse).contains("Next Actions:");
        assertThat(statusResponse).contains("Settle passive analysis: call zap_passive_scan_wait");
        assertThat(statusResponse).contains("Then review: call zap_findings_summary");
    }

    @Test
    void failedQueueStatusWithFullProgressDoesNotReturnSuccessNextActions() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueActiveScan(eq("https://example.com"), eq("true"), eq(null), eq(null)))
                .thenReturn("""
                        Scan job accepted
                        Job ID: job-failed
                        Type: ACTIVE_SCAN
                        """);
        when(scanJobQueueService.getScanJobStatus("job-failed"))
                .thenReturn("""
                        Scan job details
                        Job ID: job-failed
                        Status: FAILED
                        Progress: 100%
                        Last Error: runtime failure after progress update
                        """);

        String operationId = extractOperationId(service.startAttack("https://example.com", "true", null, null, null));
        String statusResponse = service.getAttackStatus(operationId);

        assertThat(statusResponse)
            .contains("Guided attack status.")
            .contains("Status: FAILED")
            .contains("Progress: 100%")
            .contains("Review the status/error above before trusting this scan as evidence.")
            .doesNotContain("Settle passive analysis: call zap_passive_scan_wait");
    }

    @Test
    void stopCrawlCancelsQueuedJobWhenQueuePreferred() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueSpiderScan(eq("https://example.com"), eq((String) null)))
                .thenReturn("""
                        Scan job accepted
                        Job ID: job-11
                        Type: SPIDER_SCAN
                        """);
        when(scanJobQueueService.cancelScanJob("job-11"))
                .thenReturn("Scan job job-11 cancelled");

        String startResponse = service.startCrawl("https://example.com", "http", null, null);
        String operationId = extractOperationId(startResponse);
        String stopResponse = service.stopCrawl(operationId);

        assertThat(stopResponse)
            .contains("Guided crawl stop requested.")
            .contains("Scan job job-11 cancelled");
        verify(scanJobQueueService).cancelScanJob("job-11");
    }

    @Test
    void stopAttackStopsDirectActiveScanWhenQueueNotPreferred() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(activeScanService.startActiveScan(eq("https://example.com"), eq("true"), eq("Baseline")))
                .thenReturn("""
                        Active scan started.
                        Scan ID: active-9
                        Target URL: https://example.com
                        """);
        when(activeScanService.stopActiveScan("active-9"))
                .thenReturn("Active scan stop requested for ID: active-9");

        String startResponse = service.startAttack("https://example.com", "true", "Baseline", null, null);
        String operationId = extractOperationId(startResponse);
        String stopResponse = service.stopAttack(operationId);

        assertThat(stopResponse).contains("Guided attack stop requested.");
        assertThat(stopResponse).contains("Active scan stop requested for ID: active-9");
        verify(activeScanService).stopActiveScan("active-9");
    }

    @Test
    void rejectsLegacyForgeableGuidedOperationIds() {
        String forgedPayload = "v1|attack|queue|active|job-7";
        String forgedOperationId = "zop_" + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getAttackStatus(forgedOperationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported format");
    }

    @Test
    void startCrawlExplainsQueuedAutoStrategySelection() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueSpiderScan(eq("https://example.com"), eq((String) null)))
                .thenReturn("""
                        Scan job accepted
                        Job ID: job-21
                        Type: SPIDER_SCAN
                        """);

        String startResponse = service.startCrawl("https://example.com", "auto", null, null);

        assertThat(startResponse).contains("Execution Mode: queue");
        assertThat(startResponse).contains("Strategy: http");
        assertThat(startResponse).contains("Auto strategy in queued mode currently selects the HTTP spider by default");
    }

    @Test
    void startCrawlUsesAuthenticatedSpiderWhenPreparedFormSessionProvided() {
        PreparedAuthSession session = preparedFormSession("auth-1", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-1")).thenReturn(session);
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(spiderScanService.startSpiderScanAsUser(eq("1"), eq("7"), eq("https://app.example.com"), eq((String) null), eq("true"), eq("false")))
                .thenReturn("""
                        Direct authenticated spider scan started.
                        Scan ID: auth-spider-1
                        Target URL: https://app.example.com
                        """);

        String response = service.startCrawl("https://app.example.com", "http", null, "auth-1");

        assertThat(response).contains("Guided crawl started.");
        assertThat(response).contains("Authenticated Session: auth-1");
        assertThat(response).contains("Context ID: 1");
        assertThat(response).contains("User ID: 7");
        assertThat(response).contains("Authenticated guided crawl applied the prepared form-login session");
    }

    @Test
    void startAttackUsesQueuedAuthenticatedPathWhenPreparedFormSessionProvided() {
        PreparedAuthSession session = preparedFormSession("auth-2", "https://app.example.com", "11", "17");
        when(guidedAuthSessionService.getPreparedSession("auth-2")).thenReturn(session);
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueActiveScanAsUser(eq("11"), eq("17"), eq("https://app.example.com"), eq("true"), eq("Baseline"), eq((String) null)))
                .thenReturn("""
                        Scan job accepted
                        Job ID: auth-job-9
                        Type: ACTIVE_SCAN_AS_USER
                        """);

        String response = service.startAttack("https://app.example.com", "true", "Baseline", null, "auth-2");

        assertThat(response).contains("Guided attack started.");
        assertThat(response).contains("Authenticated Session: auth-2");
        assertThat(response).contains("Execution Mode: queue");
        assertThat(response).contains("Job ID: auth-job-9");
        assertThat(response).contains("Authenticated guided attack applied the prepared form-login session");
    }

    @ParameterizedTest
    @CsvSource({"FORM,http", "BROWSER,client"})
    void preparedProfileCannotBeReusedForCrawlOnAnotherOrigin(AuthBootstrapKind kind, String strategy) {
        PreparedAuthSession session = preparedSession(kind, "auth-crawl", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-crawl")).thenReturn(session);

        assertThatThrownBy(() -> service.startCrawl("https://attacker.example", strategy, null, "auth-crawl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not authorized for the requested targetUrl origin");

        verifyNoInteractions(spiderScanService, clientSpiderService, scanJobQueueService);
    }

    @Test
    void preparedProfileCannotBeReusedForAttackOnAnotherOrigin() {
        PreparedAuthSession session = preparedFormSession("auth-attack", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-attack")).thenReturn(session);

        assertThatThrownBy(() -> service.startAttack("https://attacker.example", "true", "Baseline", null, "auth-attack"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not authorized for the requested targetUrl origin");

        verifyNoInteractions(activeScanService, scanJobQueueService);
    }

    @Test
    void startCrawlRejectsBrowserStrategyWhenAuthSessionProvided() {
        PreparedAuthSession session = preparedFormSession("auth-3", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-3")).thenReturn(session);

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", "browser", null, "auth-3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy=browser");
    }

    @Test
    void clientCrawlRejectsPreparedHttpFormAuthenticationBeforeStartingAnyScan() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedFormSession("auth-client", "https://app.example.com", "1", "7"));

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", "client", null, "auth-client"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy=client requires a prepared browser authentication session");
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void preparedBrowserSessionRoutesDirectClientCrawlAndNativeLifecycle() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(clientSpiderService.startClientSpider("https://app.example.com", null, "shop-auth", "zap-scan-user"))
                .thenReturn("Direct Client Spider scan started.\nScan ID: client-auth-1");
        when(clientSpiderService.getClientSpiderStatus("client-auth-1")).thenReturn("Progress: 20%");
        when(clientSpiderService.stopClientSpider("client-auth-1")).thenReturn("Client Spider stop requested");

        String response = service.startCrawl("https://app.example.com", "client", null, "auth-client");
        String operationId = extractOperationId(response);
        service.getCrawlStatus(operationId);
        service.stopCrawl(operationId);

        assertThat(response).contains("Strategy: client", "Execution Mode: direct", "Authenticated Session: auth-client",
                "prepared browser session", "browser session supports Client Spider only")
                .doesNotContain("call zap_attack_start", "env:SHOP_PASSWORD");
        verify(clientSpiderService).startClientSpider("https://app.example.com", null, "shop-auth", "zap-scan-user");
        verify(clientSpiderService).getClientSpiderStatus("client-auth-1");
        verify(clientSpiderService).stopClientSpider("client-auth-1");
        verifyNoInteractions(spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void preparedBrowserSessionRoutesQueuedClientCrawlWithNamesAndIdempotencyKey() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueClientSpiderScan("https://app.example.com", null, "shop-auth", "zap-scan-user", "crawl-key"))
                .thenReturn("Scan job accepted\nJob ID: client-auth-job\nType: CLIENT_SPIDER");
        when(scanJobQueueService.getScanJobStatus("client-auth-job"))
                .thenReturn("Status: RUNNING", "Status: CANCELLED");
        when(scanJobQueueService.cancelScanJob("client-auth-job")).thenReturn("Status: CANCELLED");

        String response = service.startCrawl("https://app.example.com", "client", "crawl-key", "auth-client");
        String operationId = extractOperationId(response);

        assertThat(response).contains("Strategy: client", "Execution Mode: queue", "Authenticated Session: auth-client");
        assertThat(service.getCrawlStatus(operationId)).contains("Status: RUNNING");
        assertThat(service.stopCrawl(operationId)).contains("Status: CANCELLED");
        assertThat(service.getCrawlStatus(operationId))
                .contains("Status: CANCELLED", "Review the status/error above before trusting this scan as evidence")
                .doesNotContain("Continue security testing: call zap_attack_start", "Continue: call zap_crawl_status");
        verify(scanJobQueueService).queueClientSpiderScan("https://app.example.com", null, "shop-auth", "zap-scan-user", "crawl-key");
        verify(scanJobQueueService).cancelScanJob("client-auth-job");
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService);
    }

    @Test
    void authenticatedQueuedClientFailureRemainsVisibleWithoutAnonymousFallback() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.QUEUE);
        when(scanJobQueueService.queueClientSpiderScan("https://app.example.com", null, "shop-auth", "zap-scan-user", null))
                .thenReturn("Scan job accepted\nJob ID: client-auth-failed\nType: CLIENT_SPIDER");
        when(scanJobQueueService.getScanJobStatus("client-auth-failed"))
                .thenReturn("Status: FAILED\nLast Error: Browser authentication failed\nDead Letter: true");

        String operationId = extractOperationId(service.startCrawl("https://app.example.com", "client", null, "auth-client"));

        assertThat(service.getCrawlStatus(operationId))
                .contains("Status: FAILED", "Browser authentication failed", "Retry only after fixing")
                .doesNotContain("Continue security testing: call zap_attack_start", "Continue: call zap_crawl_status");
        verify(scanJobQueueService, org.mockito.Mockito.never())
                .queueClientSpiderScan("https://app.example.com", null, null);
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", "http", "browser"})
    void preparedBrowserSessionRejectsOtherCrawlStrategies(String strategy) {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", strategy, null, "auth-client"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require strategy=client");
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void preparedBrowserSessionRejectsActiveScanBeforeLaunch() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));

        assertThatThrownBy(() -> service.startAttack("https://app.example.com", "true", null, null, "auth-client"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("require strategy=client");
        verifyNoInteractions(activeScanService, scanJobQueueService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preparedBrowserSessionRequiresBothContextAndUserNames(boolean missingContextName) {
        PreparedAuthSession session = preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(new PreparedAuthSession(session.sessionId(), session.profileId(), session.authKind(),
                        session.providerId(), session.target(), session.authorizedOrigin(), session.credentialReference(),
                        missingContextName ? null : session.contextName(), session.contextId(),
                        missingContextName ? session.zapUserName() : " ", session.userId(),
                        session.headerName(), session.loginUrl(), session.engineBound()));

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", "client", null, "auth-client"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing ZAP context/user names");
        verifyNoInteractions(clientSpiderService, spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void authenticatedClientFailureDoesNotFallBackToAnonymousCrawl() {
        when(guidedAuthSessionService.getPreparedSession("auth-client"))
                .thenReturn(preparedSession(AuthBootstrapKind.BROWSER, "auth-client", "https://app.example.com", "1", "7"));
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        ZapApiException failure = new ZapApiException("Browser authentication failed", new RuntimeException("login failed"));
        when(clientSpiderService.startClientSpider("https://app.example.com", null, "shop-auth", "zap-scan-user"))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", "client", null, "auth-client"))
                .isSameAs(failure);
        verify(clientSpiderService, org.mockito.Mockito.never()).startClientSpider("https://app.example.com", null);
        verifyNoInteractions(spiderScanService, ajaxSpiderService, scanJobQueueService);
    }

    @Test
    void startAttackRejectsNonFormAuthSessionForGuidedExecution() {
        PreparedAuthSession session = new PreparedAuthSession(
                "auth-header",
                "orders-api-key",
                AuthBootstrapKind.API_KEY,
                "gateway-header-reference",
                new TargetDescriptor(TargetDescriptor.Kind.API, "https://api.example.com", "https://api.example.com"),
                HttpOrigin.fromConfiguredOrigin("https://api.example.com"),
                "env:ORDERS_API_KEY",
                null,
                null,
                null,
                null,
                "X-API-Key",
                null,
                false
        );
        when(guidedAuthSessionService.getPreparedSession("auth-header")).thenReturn(session);

        assertThatThrownBy(() -> service.startAttack("https://api.example.com", "true", "Baseline", null, "auth-header"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("form-login sessions only");
    }

    @Test
    void generateGuidedReportIncludesScopeInResponse() {
        when(reportService.generateReport("traditional-html-plus", "light", "https://example.com/admin"))
                .thenReturn("/tmp/report.html");

        String response = service.generateGuidedReport("https://example.com/admin", "html", "light");

        assertThat(response).contains("Guided report generated.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("Path: /tmp/report.html");
        assertThat(response).contains("Next Actions:");
        assertThat(response).contains("Report readback: call zap_report_read with the Path above");
        assertThat(response).contains("Internal evidence: call zap_scan_history_release_evidence with target filter https://example.com/admin");
        assertThat(response).contains("Customer summary: call zap_scan_history_customer_handoff");

        int readbackIndex = response.indexOf("Report readback: call zap_report_read");
        int evidenceIndex = response.indexOf("Internal evidence: call zap_scan_history_release_evidence");
        int handoffIndex = response.indexOf("Customer summary: call zap_scan_history_customer_handoff");
        assertThat(readbackIndex).isLessThan(evidenceIndex);
        assertThat(evidenceIndex).isLessThan(handoffIndex);
    }

    @Test
    void readGuidedReportAddsEvidenceNextActions() {
        when(reportService.readReport("/tmp/report.html", 1000))
                .thenReturn("""
                        Report artifact
                        Path: /tmp/report.html
                        Characters Returned: 42

                        <html>report</html>
                        """);

        String response = service.readGuidedReport("/tmp/report.html", 1000);

        assertThat(response).contains("Guided report readback.");
        assertThat(response).contains("Path: /tmp/report.html");
        assertThat(response).contains("review the generated artifact");
        assertThat(response).contains("Next Actions:");
        assertThat(response).contains("Internal evidence: call zap_scan_history_release_evidence");
        assertThat(response).contains("Customer summary: call zap_scan_history_customer_handoff");
        assertThat(response).contains("<html>report</html>");
    }

    @Test
    void guidedFindingsSummaryAddsTriageContextForAgents() {
        when(findingsService.getFindingsSummary("https://example.com/admin"))
                .thenReturn("# Findings Summary");

        String response = service.getGuidedFindingsSummary("https://example.com/admin");

        assertThat(response).contains("Guided findings summary.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("Use: first-pass triage");
        assertThat(response).contains("Next Actions:");
        assertThat(response).contains("Drill down: call zap_findings_details");
        assertThat(response).contains("Report: call zap_report_generate");
        assertThat(response).contains("# Findings Summary");
    }

    @Test
    void guidedFindingsDetailsAddsModeAndFilterContextForAgents() {
        when(findingsService.getAlertInstances("https://example.com/admin", "40018", "SQL Injection", 5))
                .thenReturn("Alert instances returned: 1 of 1");

        String response = service.getGuidedFindingsDetails(
                "https://example.com/admin",
                "40018",
                "SQL Injection",
                true,
                5
        );

        assertThat(response).contains("Guided findings details.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("Mode: raw instances");
        assertThat(response).contains("Plugin ID Filter: 40018");
        assertThat(response).contains("Alert Name Filter: SQL Injection");
        assertThat(response).contains("Requested Limit: 5");
        assertThat(response).contains("inspect concrete URLs, params, evidence, and attack samples");
        assertThat(response).contains("Next Actions:");
        assertThat(response).contains("Report: call zap_report_generate");
        assertThat(response).contains("Handoff: after a report exists, call zap_scan_history_release_evidence");
        assertThat(response).contains("Alert instances returned: 1 of 1");
    }

    @Test
    void unsupportedGatewayEngineCapabilityFailsWithControlledMessage() {
        EngineAdapter unsupportedEngineAdapter = mock(EngineAdapter.class);
        when(unsupportedEngineAdapter.displayName()).thenReturn("Metadata Only Engine");
        GuidedScanWorkflowService localWorkflow = new GuidedScanWorkflowService(
                executionModeResolver,
                spiderScanService,
                ajaxSpiderService,
                clientSpiderService,
                activeScanService,
                scanJobQueueService,
                guidedAuthSessionService,
                unsupportedEngineAdapter,
                new GatewayRecordFactory()
        );

        assertThatThrownBy(() -> localWorkflow.startAttack("https://example.com", "true", "Baseline", null, null))
                .isInstanceOf(UnsupportedEngineCapabilityException.class)
                .hasMessage("Engine 'Metadata Only Engine' does not support guided attack.");
    }

    @Test
    void findingsSummaryRejectsEngineWithoutFindingsCapability() {
        EngineAdapter unsupportedEngineAdapter = unsupportedEngineAdapter();
        GuidedSecurityToolsService localService = serviceWithEngine(unsupportedEngineAdapter);

        assertThatThrownBy(() -> localService.getGuidedFindingsSummary("https://example.com"))
                .isInstanceOfSatisfying(UnsupportedEngineCapabilityException.class, exception -> {
                    assertThat(exception).hasMessage("Engine 'Metadata Only Engine' does not support findings read.");
                    assertThat(exception.capability()).isEqualTo(EngineCapability.FINDINGS_READ);
                });
        verifyNoInteractions(findingsService);
    }

    @Test
    void reportGenerationRejectsEngineWithoutReportCapability() {
        EngineAdapter unsupportedEngineAdapter = unsupportedEngineAdapter();
        GuidedSecurityToolsService localService = serviceWithEngine(unsupportedEngineAdapter);

        assertThatThrownBy(() -> localService.generateGuidedReport("https://example.com", "html", "light"))
                .isInstanceOfSatisfying(UnsupportedEngineCapabilityException.class, exception -> {
                    assertThat(exception).hasMessage("Engine 'Metadata Only Engine' does not support report generation.");
                    assertThat(exception.capability()).isEqualTo(EngineCapability.REPORT_GENERATE);
                });
        verifyNoInteractions(reportService);
    }

    private EngineAdapter unsupportedEngineAdapter() {
        EngineAdapter unsupportedEngineAdapter = mock(EngineAdapter.class);
        when(unsupportedEngineAdapter.displayName()).thenReturn("Metadata Only Engine");
        return unsupportedEngineAdapter;
    }

    private GuidedSecurityToolsService serviceWithEngine(EngineAdapter localEngineAdapter) {
        return new GuidedSecurityToolsService(
                guidedScanWorkflowService,
                reportService,
                findingsService,
                openApiService,
                localEngineAdapter,
                new GatewayRecordFactory()
        );
    }

    private String extractOperationId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Operation ID: ")) {
                return line.substring("Operation ID: ".length()).trim();
            }
        }
        throw new AssertionError("No operation ID found in response: " + response);
    }

    private PreparedAuthSession preparedFormSession(String sessionId, String targetUrl, String contextId, String userId) {
        return preparedSession(AuthBootstrapKind.FORM, sessionId, targetUrl, contextId, userId);
    }

    private PreparedAuthSession preparedSession(AuthBootstrapKind kind, String sessionId, String targetUrl,
                                               String contextId, String userId) {
        return new PreparedAuthSession(
                sessionId,
                "shop-form-auth",
                kind,
                "zap-form-login",
                new TargetDescriptor(TargetDescriptor.Kind.WEB, targetUrl, "shop-auth"),
                HttpOrigin.fromUrl(targetUrl),
                "env:SHOP_PASSWORD",
                "shop-auth",
                contextId,
                "zap-scan-user",
                userId,
                null,
                targetUrl + "/login",
                true
        );
    }
}
