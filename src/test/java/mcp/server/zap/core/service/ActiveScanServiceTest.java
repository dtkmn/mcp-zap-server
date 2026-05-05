package mcp.server.zap.core.service;

import java.util.List;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRuleMutation;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.PolicyCategorySnapshot;
import mcp.server.zap.core.gateway.EngineScanExecution.ScannerRuleSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ActiveScanServiceTest {
    private EngineScanExecution engineScanExecution;
    private ActiveScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeEach
    void setup() {
        engineScanExecution = mock(EngineScanExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxActiveScanDurationInMins()).thenReturn(30);
        when(scanLimitProperties.getHostPerScan()).thenReturn(5);
        when(scanLimitProperties.getThreadPerHost()).thenReturn(10);

        service = new ActiveScanService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void startActiveScanJobReturnsScanId() {
        ActiveScanRequest request = new ActiveScanRequest("http://example.com", "true", "Default Policy", 30, 5, 10);
        when(engineScanExecution.startActiveScan(request)).thenReturn("101");

        String scanId = service.startActiveScanJob("http://example.com", "true", "Default Policy");

        assertEquals("101", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startActiveScan(request);
    }

    @Test
    void startActiveScanAsUserJobReturnsScanId() {
        AuthenticatedActiveScanRequest request = new AuthenticatedActiveScanRequest(
                "1", "3", "http://example.com", "true", "Default Policy", 30, 5, 10);
        when(engineScanExecution.startActiveScanAsUser(request)).thenReturn("202");

        String scanId = service.startActiveScanAsUserJob("1", "3", "http://example.com", null, "Default Policy");

        assertEquals("202", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startActiveScanAsUser(request);
    }

    @Test
    void getActiveScanProgressPercentReturnsValue() {
        when(engineScanExecution.readActiveScanProgressPercent("1")).thenReturn(42);

        int progress = service.getActiveScanProgressPercent("1");

        assertEquals(42, progress);
    }

    @Test
    void listScanPoliciesReturnsAvailableNames() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        String result = service.listScanPolicies();

        assertTrue(result.contains("Available active-scan policies:"));
        assertTrue(result.contains("Default Policy"));
        assertTrue(result.contains("API"));
        assertTrue(result.contains("zap_scan_policy_view"));
    }

    @Test
    void viewScanPolicyReturnsCategoriesAndRules() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyCategories("Default Policy")).thenReturn(policyCategoriesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy")).thenReturn(defaultPolicyScannersResponse());

        String result = service.viewScanPolicy("default policy", null, "2");

        assertTrue(result.contains("Active-scan policy view:"));
        assertTrue(result.contains("Policy: Default Policy"));
        assertTrue(result.contains("Rules: 3 total, 2 enabled, 1 overridden"));
        assertTrue(result.contains("Current rule overrides:"));
        assertTrue(result.contains("40012 | Cross Site Scripting (Reflected)"));
        assertTrue(result.contains("enabled=no"));
        assertTrue(result.contains("Showing: 2 of 3 matched rules"));
    }

    @Test
    void setScanPolicyRuleStateUpdatesRules() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy"))
                .thenReturn(defaultPolicyScannersResponse(), updatedDefaultPolicyScannersResponse());

        String result = service.setScanPolicyRuleState("Default Policy", "40012, 40018", "false", "LOW", "HIGH");

        verify(engineScanExecution).updateActiveScanRuleState(new ActiveScanRuleMutation(
                "Default Policy",
                List.of("40012", "40018"),
                false,
                "LOW",
                "HIGH"
        ));
        assertTrue(result.contains("Active-scan policy updated."));
        assertTrue(result.contains("Requested enabled state: no"));
        assertTrue(result.contains("Requested attack strength: LOW"));
        assertTrue(result.contains("Requested alert threshold: HIGH"));
        assertTrue(result.contains("40012 | Cross Site Scripting (Reflected) | enabled=no | attack=LOW | threshold=HIGH"));
        assertTrue(result.contains("40018 | SQL Injection | enabled=no | attack=LOW | threshold=HIGH"));
    }

    @Test
    void setScanPolicyRuleStateRejectsUnknownRuleId() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy")).thenReturn(defaultPolicyScannersResponse());

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.setScanPolicyRuleState("Default Policy", "99999", null, "LOW", null)
        );

        assertTrue(error.getMessage().contains("Unknown rule IDs"));
    }

    @Test
    void setScanPolicyRuleStateRejectsMissingChanges() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.setScanPolicyRuleState("Default Policy", "40012", null, null, null)
        );

        assertTrue(error.getMessage().contains("At least one of enabled, attackStrength, or alertThreshold"));
    }

    @Test
    void startActiveScanReturnsDirectMessage() {
        when(engineScanExecution.startActiveScan(new ActiveScanRequest("http://example.com", "true", null, 30, 5, 10)))
                .thenReturn("101");

        String result = service.startActiveScan("http://example.com", null, null);

        assertTrue(result.contains("Direct active scan started."));
        assertTrue(result.contains("Scan ID: 101"));
        assertTrue(result.contains("Use 'zap_active_scan_status'"));
        verify(engineScanExecution).startActiveScan(new ActiveScanRequest("http://example.com", "true", null, 30, 5, 10));
    }

    @Test
    void startActiveScanAsUserReturnsDirectMessage() {
        when(engineScanExecution.startActiveScanAsUser(new AuthenticatedActiveScanRequest(
                "1", "3", "http://example.com", "true", "Default Policy", 30, 5, 10)))
                .thenReturn("202");

        String result = service.startActiveScanAsUser("1", "3", "http://example.com", null, "Default Policy");

        assertTrue(result.contains("Direct authenticated active scan started."));
        assertTrue(result.contains("Scan ID: 202"));
        assertTrue(result.contains("Context ID: 1"));
        assertTrue(result.contains("User ID: 3"));
    }

    @Test
    void getActiveScanStatusReturnsDirectMessage() {
        when(engineScanExecution.readActiveScanProgressPercent("1")).thenReturn(42);

        String result = service.getActiveScanStatus("1");

        assertTrue(result.contains("Direct active scan status:"));
        assertTrue(result.contains("Progress: 42%"));
        assertTrue(result.contains("Completed: no"));
    }

    @Test
    void stopActiveScanJobCallsEngineBoundary() {
        service.stopActiveScanJob("2");

        verify(engineScanExecution).stopActiveScan("2");
    }

    @Test
    void stopActiveScanReturnsDirectMessage() {
        String result = service.stopActiveScan("2");

        assertTrue(result.contains("Direct active scan stopped."));
        assertTrue(result.contains("Scan ID: 2"));
        verify(engineScanExecution).stopActiveScan("2");
    }

    @Test
    void stopActiveScanJobHandlesException() {
        doThrow(new ZapApiException("boom", new RuntimeException("boom")))
                .when(engineScanExecution).stopActiveScan("2");

        assertThrowsExactly(ZapApiException.class, () -> service.stopActiveScanJob("2"));
    }

    private List<String> scanPolicyNamesResponse() {
        return List.of("Default Policy", "API");
    }

    private List<PolicyCategorySnapshot> policyCategoriesResponse() {
        return List.of(
                new PolicyCategorySnapshot("0", "Information Gathering", true, "DEFAULT", "DEFAULT"),
                new PolicyCategorySnapshot("4", "Injection", true, "DEFAULT", "DEFAULT")
        );
    }

    private List<ScannerRuleSnapshot> defaultPolicyScannersResponse() {
        return List.of(
                scannerRule("6", "Path Traversal", "0", true, "DEFAULT", "DEFAULT"),
                scannerRule("40012", "Cross Site Scripting (Reflected)", "4", false, "DEFAULT", "OFF"),
                scannerRule("40018", "SQL Injection", "4", true, "DEFAULT", "DEFAULT")
        );
    }

    private List<ScannerRuleSnapshot> updatedDefaultPolicyScannersResponse() {
        return List.of(
                scannerRule("6", "Path Traversal", "0", true, "DEFAULT", "DEFAULT"),
                scannerRule("40012", "Cross Site Scripting (Reflected)", "4", false, "LOW", "HIGH"),
                scannerRule("40018", "SQL Injection", "4", false, "LOW", "HIGH")
        );
    }

    private ScannerRuleSnapshot scannerRule(String id,
                                            String name,
                                            String policyId,
                                            boolean enabled,
                                            String attackStrength,
                                            String alertThreshold) {
        return new ScannerRuleSnapshot(
                id,
                name,
                policyId,
                enabled,
                attackStrength,
                alertThreshold,
                "release",
                "release",
                List.of()
        );
    }
}
