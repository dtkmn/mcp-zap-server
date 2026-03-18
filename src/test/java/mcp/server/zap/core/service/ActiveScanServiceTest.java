package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Ascan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ActiveScanServiceTest {
    private Ascan ascan;
    private ActiveScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        ascan = mock(Ascan.class);
        clientApi.ascan = ascan;

        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxActiveScanDurationInMins()).thenReturn(30);
        when(scanLimitProperties.getHostPerScan()).thenReturn(5);
        when(scanLimitProperties.getThreadPerHost()).thenReturn(10);

        service = new ActiveScanService(clientApi, urlValidationService, scanLimitProperties);
    }

    @Test
    void startActiveScanJobReturnsScanId() throws Exception {
        when(ascan.scan(any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String scanId = service.startActiveScanJob("http://example.com", "true", "Default Policy");

        assertEquals("101", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(ascan).scan("http://example.com", "true", "false", "Default Policy", null, null);
    }

    @Test
    void startActiveScanAsUserJobReturnsScanId() throws Exception {
        when(ascan.scanAsUser(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "202"));

        String scanId = service.startActiveScanAsUserJob("1", "3", "http://example.com", null, "Default Policy");

        assertEquals("202", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(ascan).scanAsUser("http://example.com", "1", "3", "true", "Default Policy", null, null);
    }

    @Test
    void getActiveScanProgressPercentReturnsValue() throws Exception {
        when(ascan.status("1")).thenReturn(new ApiResponseElement("status", "42"));

        int progress = service.getActiveScanProgressPercent("1");

        assertEquals(42, progress);
    }

    @Test
    void listScanPoliciesReturnsAvailableNames() throws Exception {
        when(ascan.scanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        String result = service.listScanPolicies();

        assertTrue(result.contains("Available active-scan policies:"));
        assertTrue(result.contains("Default Policy"));
        assertTrue(result.contains("API"));
        assertTrue(result.contains("zap_scan_policy_view"));
    }

    @Test
    void viewScanPolicyReturnsCategoriesAndRules() throws Exception {
        when(ascan.scanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(ascan.policies("Default Policy", null)).thenReturn(policyCategoriesResponse());
        when(ascan.scanners("Default Policy", null)).thenReturn(defaultPolicyScannersResponse());

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
    void setScanPolicyRuleStateUpdatesRules() throws Exception {
        when(ascan.scanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(ascan.scanners("Default Policy", null))
                .thenReturn(defaultPolicyScannersResponse(), updatedDefaultPolicyScannersResponse());

        String result = service.setScanPolicyRuleState("Default Policy", "40012, 40018", "false", "LOW", "HIGH");

        verify(ascan).disableScanners("40012,40018", "Default Policy");
        verify(ascan).setScannerAttackStrength("40012", "LOW", "Default Policy");
        verify(ascan).setScannerAttackStrength("40018", "LOW", "Default Policy");
        verify(ascan).setScannerAlertThreshold("40012", "HIGH", "Default Policy");
        verify(ascan).setScannerAlertThreshold("40018", "HIGH", "Default Policy");
        assertTrue(result.contains("Active-scan policy updated."));
        assertTrue(result.contains("Requested enabled state: no"));
        assertTrue(result.contains("Requested attack strength: LOW"));
        assertTrue(result.contains("Requested alert threshold: HIGH"));
        assertTrue(result.contains("40012 | Cross Site Scripting (Reflected) | enabled=no | attack=LOW | threshold=HIGH"));
        assertTrue(result.contains("40018 | SQL Injection | enabled=no | attack=LOW | threshold=HIGH"));
    }

    @Test
    void setScanPolicyRuleStateRejectsUnknownRuleId() throws Exception {
        when(ascan.scanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(ascan.scanners("Default Policy", null)).thenReturn(defaultPolicyScannersResponse());

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.setScanPolicyRuleState("Default Policy", "99999", null, "LOW", null)
        );

        assertTrue(error.getMessage().contains("Unknown rule IDs"));
    }

    @Test
    void setScanPolicyRuleStateRejectsMissingChanges() throws Exception {
        when(ascan.scanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        IllegalArgumentException error = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> service.setScanPolicyRuleState("Default Policy", "40012", null, null, null)
        );

        assertTrue(error.getMessage().contains("At least one of enabled, attackStrength, or alertThreshold"));
    }

    @Test
    void startActiveScanReturnsDirectMessage() throws Exception {
        when(ascan.scan(any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String result = service.startActiveScan("http://example.com", null, null);

        assertTrue(result.contains("Direct active scan started."));
        assertTrue(result.contains("Scan ID: 101"));
        assertTrue(result.contains("Use 'zap_active_scan_status'"));
        verify(ascan).scan("http://example.com", "true", "false", null, null, null);
    }

    @Test
    void startActiveScanAsUserReturnsDirectMessage() throws Exception {
        when(ascan.scanAsUser(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "202"));

        String result = service.startActiveScanAsUser("1", "3", "http://example.com", null, "Default Policy");

        assertTrue(result.contains("Direct authenticated active scan started."));
        assertTrue(result.contains("Scan ID: 202"));
        assertTrue(result.contains("Context ID: 1"));
        assertTrue(result.contains("User ID: 3"));
    }

    @Test
    void getActiveScanStatusReturnsDirectMessage() throws Exception {
        when(ascan.status("1")).thenReturn(new ApiResponseElement("status", "42"));

        String result = service.getActiveScanStatus("1");

        assertTrue(result.contains("Direct active scan status:"));
        assertTrue(result.contains("Progress: 42%"));
        assertTrue(result.contains("Completed: no"));
    }

    @Test
    void stopActiveScanJobCallsApi() throws Exception {
        service.stopActiveScanJob("2");

        verify(ascan).stop("2");
    }

    @Test
    void stopActiveScanReturnsDirectMessage() throws Exception {
        String result = service.stopActiveScan("2");

        assertTrue(result.contains("Direct active scan stopped."));
        assertTrue(result.contains("Scan ID: 2"));
        verify(ascan).stop("2");
    }

    @Test
    void stopActiveScanJobHandlesException() throws Exception {
        doThrow(new ClientApiException("boom", null)).when(ascan).stop("2");

        assertThrowsExactly(ZapApiException.class, () -> service.stopActiveScanJob("2"));
    }

    private ApiResponseList scanPolicyNamesResponse() {
        return new ApiResponseList("scanPolicyNames", List.of(
                new ApiResponseElement("scanPolicyName", "Default Policy"),
                new ApiResponseElement("scanPolicyName", "API")
        ));
    }

    private ApiResponseList policyCategoriesResponse() {
        return new ApiResponseList("policies", List.of(
                responseSet("policy",
                        "id", "0",
                        "name", "Information Gathering",
                        "enabled", "true",
                        "attackStrength", "DEFAULT",
                        "alertThreshold", "DEFAULT"),
                responseSet("policy",
                        "id", "4",
                        "name", "Injection",
                        "enabled", "true",
                        "attackStrength", "DEFAULT",
                        "alertThreshold", "DEFAULT")
        ));
    }

    private ApiResponseList defaultPolicyScannersResponse() {
        return new ApiResponseList("scanners", List.of(
                scannerRule("6", "Path Traversal", "0", true, "DEFAULT", "DEFAULT"),
                scannerRule("40012", "Cross Site Scripting (Reflected)", "4", false, "DEFAULT", "OFF"),
                scannerRule("40018", "SQL Injection", "4", true, "DEFAULT", "DEFAULT")
        ));
    }

    private ApiResponseList updatedDefaultPolicyScannersResponse() {
        return new ApiResponseList("scanners", List.of(
                scannerRule("6", "Path Traversal", "0", true, "DEFAULT", "DEFAULT"),
                scannerRule("40012", "Cross Site Scripting (Reflected)", "4", false, "LOW", "HIGH"),
                scannerRule("40018", "SQL Injection", "4", false, "LOW", "HIGH")
        ));
    }

    private ApiResponseSet scannerRule(String id,
                                       String name,
                                       String policyId,
                                       boolean enabled,
                                       String attackStrength,
                                       String alertThreshold) {
        Map<String, ApiResponse> values = new LinkedHashMap<>();
        values.put("id", new ApiResponseElement("id", id));
        values.put("name", new ApiResponseElement("name", name));
        values.put("policyId", new ApiResponseElement("policyId", policyId));
        values.put("enabled", new ApiResponseElement("enabled", Boolean.toString(enabled)));
        values.put("attackStrength", new ApiResponseElement("attackStrength", attackStrength));
        values.put("alertThreshold", new ApiResponseElement("alertThreshold", alertThreshold));
        values.put("quality", new ApiResponseElement("quality", "release"));
        values.put("status", new ApiResponseElement("status", "release"));
        values.put("dependencies", new ApiResponseList("dependencies", List.of()));
        return new ApiResponseSet("scanner", values);
    }

    private ApiResponseSet responseSet(String name, String... keyValues) {
        Map<String, ApiResponse> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], new ApiResponseElement(keyValues[i], keyValues[i + 1]));
        }
        return new ApiResponseSet(name, values);
    }
}
