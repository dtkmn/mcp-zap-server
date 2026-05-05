package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.ArtifactRecord;
import mcp.server.zap.core.gateway.EngineAdapter;
import mcp.server.zap.core.gateway.EngineCapability;
import mcp.server.zap.core.gateway.FindingRecord;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.ScanRunRecord;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.gateway.UnsupportedEngineCapabilityException;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.service.auth.bootstrap.AuthBootstrapKind;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.auth.bootstrap.PreparedAuthSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuidedSecurityToolsServiceTest {

    private GuidedExecutionModeResolver executionModeResolver;
    private SpiderScanService spiderScanService;
    private AjaxSpiderService ajaxSpiderService;
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
                        """);
        when(spiderScanService.getSpiderScanStatus("spider-1"))
                .thenReturn("Direct spider scan status:\nScan ID: spider-1\nProgress: 45%");

        String startResponse = service.startCrawl("https://example.com", "auto", null, null);
        String operationId = extractOperationId(startResponse);
        String statusResponse = service.getCrawlStatus(operationId);

        assertThat(startResponse).contains("Guided crawl started.");
        assertThat(startResponse).contains("Execution Mode: direct");
        assertThat(startResponse).contains("Strategy: http");
        assertThat(statusResponse).contains("Guided crawl status.");
        assertThat(statusResponse).contains("Progress: 45%");
    }

    @Test
    void startCrawlFallsBackToAjaxWhenAutoStrategyFailsInDirectMode() {
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(spiderScanService.startSpiderScan(eq("https://spa.example.com")))
                .thenThrow(new ZapApiException("regular spider blocked", new RuntimeException("blocked")));
        when(ajaxSpiderService.startAjaxSpider(eq("https://spa.example.com")))
                .thenReturn("AJAX Spider scan started successfully for URL: https://spa.example.com");

        String response = service.startCrawl("https://spa.example.com", "auto", null, null);

        assertThat(response).contains("Guided crawl started.");
        assertThat(response).contains("Strategy: browser");
        assertThat(response).contains("Auto strategy fell back");
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

        assertThat(startResponse).contains("Guided attack started.");
        assertThat(startResponse).contains("Execution Mode: queue");
        assertThat(statusResponse).contains("Guided attack status.");
        assertThat(statusResponse).contains("Job ID: job-7");
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

        assertThat(stopResponse).contains("Guided crawl stop requested.");
        assertThat(stopResponse).contains("Scan job job-11 cancelled");
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

    @Test
    void startCrawlRejectsBrowserStrategyWhenAuthSessionProvided() {
        PreparedAuthSession session = preparedFormSession("auth-3", "https://app.example.com", "1", "7");
        when(guidedAuthSessionService.getPreparedSession("auth-3")).thenReturn(session);

        assertThatThrownBy(() -> service.startCrawl("https://app.example.com", "browser", null, "auth-3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy=browser");
    }

    @Test
    void startAttackRejectsNonFormAuthSessionForGuidedExecution() {
        PreparedAuthSession session = new PreparedAuthSession(
                "auth-header",
                "orders-api-key",
                AuthBootstrapKind.API_KEY,
                "gateway-header-reference",
                new TargetDescriptor(TargetDescriptor.Kind.API, "https://api.example.com", "https://api.example.com"),
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
    }

    @Test
    void guidedFindingsSummaryAddsTriageContextForAgents() {
        when(findingsService.getFindingsSummary("https://example.com/admin"))
                .thenReturn("# Findings Summary");

        String response = service.getGuidedFindingsSummary("https://example.com/admin");

        assertThat(response).contains("Guided findings summary.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("Use: first-pass triage");
        assertThat(response).contains("Next Step: call zap_findings_details");
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
        assertThat(response).contains("Alert instances returned: 1 of 1");
    }

    @Test
    void startAttackRoutesThroughGatewayScanRunRecordWithoutChangingResponse() {
        EngineAdapter localEngineAdapter = mock(EngineAdapter.class);
        GatewayRecordFactory localGatewayRecordFactory = mock(GatewayRecordFactory.class);
        TargetDescriptor target = new TargetDescriptor(TargetDescriptor.Kind.WEB, "https://example.com/api", "example.com");
        ScanRunRecord scanRun = new ScanRunRecord(
                "zap",
                "active-9",
                "attack",
                "started",
                target,
                "direct",
                "active-9"
        );
        GuidedScanWorkflowService localWorkflow = new GuidedScanWorkflowService(
                executionModeResolver,
                spiderScanService,
                ajaxSpiderService,
                activeScanService,
                scanJobQueueService,
                guidedAuthSessionService,
                localEngineAdapter,
                localGatewayRecordFactory
        );
        when(executionModeResolver.resolveDefaultMode()).thenReturn(GuidedExecutionModeResolver.ExecutionMode.DIRECT);
        when(localGatewayRecordFactory.targetFromUrl("https://example.com/api", TargetDescriptor.Kind.WEB)).thenReturn(target);
        when(localGatewayRecordFactory.scanRun(
                localEngineAdapter,
                "active-9",
                "attack",
                "started",
                target,
                "direct",
                "active-9"
        )).thenReturn(scanRun);
        when(activeScanService.startActiveScan(eq("https://example.com/api"), eq("true"), eq("Baseline")))
                .thenReturn("""
                        Active scan started.
                        Scan ID: active-9
                        Target URL: https://example.com/api
                        """);

        String response = localWorkflow.startAttack("https://example.com/api", "true", "Baseline", null, null);

        assertThat(response).contains("Guided attack started.");
        assertThat(response).contains("Execution Mode: direct");
        assertThat(response).contains("Scan ID: active-9");
        verify(localGatewayRecordFactory).requireCapability(localEngineAdapter, EngineCapability.GUIDED_ATTACK, "guided attack");
        verify(localGatewayRecordFactory).targetFromUrl("https://example.com/api", TargetDescriptor.Kind.WEB);
        verify(localGatewayRecordFactory).scanRun(
                localEngineAdapter,
                "active-9",
                "attack",
                "started",
                target,
                "direct",
                "active-9"
        );
    }

    @Test
    void guidedFindingsSummaryRoutesThroughGatewayFindingRecordWithoutChangingResponse() {
        EngineAdapter localEngineAdapter = mock(EngineAdapter.class);
        GatewayRecordFactory localGatewayRecordFactory = mock(GatewayRecordFactory.class);
        TargetDescriptor target = new TargetDescriptor(TargetDescriptor.Kind.WEB, "https://example.com/admin", "example.com");
        FindingRecord findingRecord = new FindingRecord(
                "zap",
                "guided-findings-summary",
                "mixed",
                "Guided findings summary",
                target,
                "summary",
                "Summary generated by the active engine"
        );
        GuidedSecurityToolsService localService = new GuidedSecurityToolsService(
                guidedScanWorkflowService,
                reportService,
                findingsService,
                openApiService,
                localEngineAdapter,
                localGatewayRecordFactory
        );
        when(localGatewayRecordFactory.optionalTarget("https://example.com/admin", TargetDescriptor.Kind.WEB)).thenReturn(target);
        when(localGatewayRecordFactory.findingSummary(localEngineAdapter, target, "summary")).thenReturn(findingRecord);
        when(findingsService.getFindingsSummary("https://example.com/admin"))
                .thenReturn("# Findings Summary");

        String response = localService.getGuidedFindingsSummary("https://example.com/admin");

        assertThat(response).contains("Guided findings summary.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("# Findings Summary");
        verify(localGatewayRecordFactory).requireCapability(localEngineAdapter, EngineCapability.FINDINGS_READ, "findings read");
        verify(localGatewayRecordFactory).optionalTarget("https://example.com/admin", TargetDescriptor.Kind.WEB);
        verify(localGatewayRecordFactory).findingSummary(localEngineAdapter, target, "summary");
    }

    @Test
    void guidedReportRoutesThroughGatewayArtifactRecordWithoutChangingResponse() {
        EngineAdapter localEngineAdapter = mock(EngineAdapter.class);
        GatewayRecordFactory localGatewayRecordFactory = mock(GatewayRecordFactory.class);
        TargetDescriptor target = new TargetDescriptor(TargetDescriptor.Kind.WEB, "https://example.com/admin", "example.com");
        ArtifactRecord artifact = new ArtifactRecord(
                "zap",
                "/tmp/report.html",
                "report",
                "/tmp/report.html",
                "text/html",
                target
        );
        GuidedSecurityToolsService localService = new GuidedSecurityToolsService(
                guidedScanWorkflowService,
                reportService,
                findingsService,
                openApiService,
                localEngineAdapter,
                localGatewayRecordFactory
        );
        when(localGatewayRecordFactory.optionalTarget("https://example.com/admin", TargetDescriptor.Kind.WEB)).thenReturn(target);
        when(localGatewayRecordFactory.reportArtifact(localEngineAdapter, target, "/tmp/report.html", "html")).thenReturn(artifact);
        when(reportService.generateReport("traditional-html-plus", "light", "https://example.com/admin"))
                .thenReturn("/tmp/report.html");

        String response = localService.generateGuidedReport("https://example.com/admin", "html", "light");

        assertThat(response).contains("Guided report generated.");
        assertThat(response).contains("Scope: https://example.com/admin");
        assertThat(response).contains("Path: /tmp/report.html");
        verify(localGatewayRecordFactory).requireCapability(localEngineAdapter, EngineCapability.REPORT_GENERATE, "report generation");
        verify(localGatewayRecordFactory).optionalTarget("https://example.com/admin", TargetDescriptor.Kind.WEB);
        verify(localGatewayRecordFactory).reportArtifact(localEngineAdapter, target, "/tmp/report.html", "html");
    }

    @Test
    void unsupportedGatewayEngineCapabilityFailsWithControlledMessage() {
        EngineAdapter unsupportedEngineAdapter = mock(EngineAdapter.class);
        when(unsupportedEngineAdapter.displayName()).thenReturn("Metadata Only Engine");
        GuidedScanWorkflowService localWorkflow = new GuidedScanWorkflowService(
                executionModeResolver,
                spiderScanService,
                ajaxSpiderService,
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

    private String extractOperationId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Operation ID: ")) {
                return line.substring("Operation ID: ".length()).trim();
            }
        }
        throw new AssertionError("No operation ID found in response: " + response);
    }

    private PreparedAuthSession preparedFormSession(String sessionId, String targetUrl, String contextId, String userId) {
        return new PreparedAuthSession(
                sessionId,
                "shop-form-auth",
                AuthBootstrapKind.FORM,
                "zap-form-login",
                new TargetDescriptor(TargetDescriptor.Kind.WEB, targetUrl, "shop-auth"),
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
