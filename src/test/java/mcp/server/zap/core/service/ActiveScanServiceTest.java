package mcp.server.zap.core.service;

import java.util.List;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRuleMutation;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.PolicyCategorySnapshot;
import mcp.server.zap.core.gateway.EngineScanExecution.ScannerRuleSnapshot;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ActiveScanServiceTest {
    private EngineScanExecution engineScanExecution;
    private ActiveScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;
    private NativeScanLaunch launch;

    @BeforeEach
    void setup() {
        engineScanExecution = mock(EngineScanExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);
        launch = mock(NativeScanLaunch.class);

        when(scanLimitProperties.getMaxActiveScanDurationInMins()).thenReturn(30);
        when(scanLimitProperties.getHostPerScan()).thenReturn(5);
        when(scanLimitProperties.getThreadPerHost()).thenReturn(10);

        service = new ActiveScanService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void listScanPoliciesReturnsAvailableNames() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        String result = service.listScanPolicies();

        assertTrue(result.contains("Available active-scan policies:"));
        assertTrue(result.contains("Default Policy"));
        assertTrue(result.contains("API"));
        verify(engineScanExecution).listActiveScanPolicyNames();
        verifyNoMoreInteractions(engineScanExecution);
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
        assertThat(result).doesNotContain("Current rule overrides:", "zap_scan_policy_rule_set");
        assertTrue(result.contains("40012 | Cross Site Scripting (Reflected)"));
        assertTrue(result.contains("enabled=no"));
        assertTrue(result.contains("Showing: 2 of 3 matched rules"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1", "100"})
    void policyRuleDetailsHonorDefaultAndExplicitLimitsWithoutDuplicatingOverrides(String limit) {
        int expectedLimit = limit == null ? 25 : Integer.parseInt(limit);
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyCategories("Default Policy")).thenReturn(policyCategoriesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy")).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 101)
                        .mapToObj(id -> scannerRule(String.valueOf(id), "Rule " + id, "4", false, "LOW", "HIGH"))
                        .toList());

        String result = service.viewScanPolicy("Default Policy", null, limit);

        assertThat(result).contains("Rules: 101 total, 0 enabled, 101 overridden",
                        "Showing: " + expectedLimit + " of 101 matched rules")
                .doesNotContain("Current rule overrides:", "- 101 | Rule 101");
        assertThat(result.lines().filter(line -> line.startsWith("- ") && line.contains(" | category=")))
                .hasSize(expectedLimit);
        verify(engineScanExecution).listActiveScanPolicyNames();
        verify(engineScanExecution).loadActiveScanPolicyCategories("Default Policy");
        verify(engineScanExecution).loadActiveScanPolicyRules("Default Policy");
        verifyNoMoreInteractions(engineScanExecution);
    }

    @ParameterizedTest
    @CsvSource({"40018,40018", "sQl,40018", "4,40012|40018", "unmatched,NONE"})
    void policyRuleFiltersDoNotLeakUnmatchedOverrides(String filter, String expectedIds) {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyCategories("Default Policy")).thenReturn(policyCategoriesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy")).thenReturn(defaultPolicyScannersResponse());

        String result = service.viewScanPolicy("Default Policy", filter, "100");

        String[] ids = "NONE".equals(expectedIds) ? new String[0] : expectedIds.split("\\|");
        assertThat(result.lines().filter(line -> line.startsWith("- ") && line.contains(" | category="))
                .map(line -> line.substring(2, line.indexOf(" | ")))).containsExactly(ids);
        assertThat(result).contains("Showing: " + ids.length + " of " + ids.length + " matched rules")
                .doesNotContain("Current rule overrides:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "invalid"})
    void invalidPolicyViewLimitsAreRejectedBeforeRuleQueries(String limit) {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        assertThatThrownBy(() -> service.viewScanPolicy("Default Policy", null, limit))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");

        verify(engineScanExecution).listActiveScanPolicyNames();
        verifyNoMoreInteractions(engineScanExecution);
    }

    @Test
    void unknownPolicyNamePointsToAvailableInspectionWithoutQueryingRules() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());

        assertThatThrownBy(() -> service.viewScanPolicy("Missing Policy", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scan policy").hasMessageContaining("zap_scan_policies_list");

        verify(engineScanExecution).listActiveScanPolicyNames();
        verifyNoMoreInteractions(engineScanExecution);
    }

    @Test
    void setScanPolicyRuleStateUpdatesRules() {
        when(engineScanExecution.listActiveScanPolicyNames()).thenReturn(scanPolicyNamesResponse());
        when(engineScanExecution.loadActiveScanPolicyRules("Default Policy"))
                .thenReturn(defaultPolicyScannersResponse())
                .thenReturn(updatedDefaultPolicyScannersResponse());

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
    void startActiveScanJobPassesDefaultsLimitsAndLaunchOwnership() {
        when(engineScanExecution.startActiveScan(
                new ActiveScanRequest("http://example.com", "true", "Baseline", 30, 5, 10), launch))
                .thenReturn("101");

        String result = service.startActiveScanJob("http://example.com", null, "Baseline", launch);

        assertEquals("101", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startActiveScan(
                new ActiveScanRequest("http://example.com", "true", "Baseline", 30, 5, 10), launch);
    }

    @Test
    void startActiveScanAsUserJobPassesAuthenticationAndLaunchOwnership() {
        when(engineScanExecution.startActiveScanAsUser(new AuthenticatedActiveScanRequest(
                "1", "3", "http://example.com", "true", "Default Policy", 30, 5, 10), launch))
                .thenReturn("202");

        String result = service.startActiveScanAsUserJob("1", "3", "http://example.com", null, "Default Policy", launch);

        assertEquals("202", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startActiveScanAsUser(new AuthenticatedActiveScanRequest(
                "1", "3", "http://example.com", "true", "Default Policy", 30, 5, 10), launch);
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
    void stopActiveScanReturnsDirectMessage() {
        String result = service.stopActiveScan("2");

        assertTrue(result.contains("Direct active scan stopped."));
        assertTrue(result.contains("Scan ID: 2"));
        verify(engineScanExecution).stopActiveScan("2");
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
