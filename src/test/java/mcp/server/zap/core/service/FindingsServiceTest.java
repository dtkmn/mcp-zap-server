package mcp.server.zap.core.service;

import java.util.List;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FindingsServiceTest {
    private EngineFindingAccess findingAccess;
    private ScanHistoryLedgerService scanHistoryLedgerService;
    private FindingsService service;

    @BeforeEach
    void setup() {
        findingAccess = mock(EngineFindingAccess.class);
        scanHistoryLedgerService = mock(ScanHistoryLedgerService.class);
        service = new FindingsService(findingAccess);
        service.setScanHistoryLedgerService(scanHistoryLedgerService);
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("http://target")).thenReturn(true);
    }

    @Test
    void getAlertDetailsReturnsGroupedSummaryWhenMultipleFamiliesMatch() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"),
                createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102"),
                createAlert("3", "40012", "Cross Site Scripting", "Medium", "High", "http://target/c", "103")
        ));

        String result = service.getAlertDetails("http://target", null, null);

        assertTrue(result.contains("Alert detail groups: 2"));
        assertTrue(result.contains("SQL Injection"));
        assertTrue(result.contains("Plugin ID: 40018"));
        assertTrue(result.contains("Cross Site Scripting"));
        assertTrue(result.contains("bounded instance view"));
        assertFalse(result.contains("zap_alert_instances"));
    }

    @Test
    void getFindingsSummaryReturnsGroupedMarkdown() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"),
                createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102"),
                createAlert("3", "40012", "Cross Site Scripting", "Medium", "High", "http://target/c", "103")
        ));

        String markdown = service.getFindingsSummary("http://target");

        assertTrue(markdown.contains("**Total Alerts:** 3"));
        assertTrue(markdown.contains("## 🔴 High Risk"));
        assertTrue(markdown.contains("**SQL Injection** (2 instances)"));
        assertTrue(markdown.contains("## 🔴 Medium Risk"));
    }

    @Test
    void getAlertDetailsReturnsDetailedViewForSingleFamily() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101")
        ));

        String result = service.getAlertDetails("http://target", "40018", null);

        assertTrue(result.contains("Alert details"));
        assertTrue(result.contains("Alert Name: SQL Injection"));
        assertTrue(result.contains("Instances: 1"));
        assertTrue(result.contains("Sample URL: http://target/a"));
        assertTrue(result.contains("Inspect bounded instances"));
        assertFalse(result.contains("zap_alert_instances"));
    }

    @Test
    void getAlertInstancesReturnsBoundedResults() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"),
                createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102")
        ));

        String result = service.getAlertInstances("http://target", "40018", null, 1);

        assertTrue(result.contains("Alert instances returned: 1 of 2"));
        assertTrue(result.contains("Alert ID: 1"));
        assertTrue(result.contains("Message ID: 101"));
        assertTrue(result.contains("Results truncated."));
    }

    @Test
    void exportFindingsSnapshotReturnsStableJsonPayload() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101")
        ));

        String snapshot = service.exportFindingsSnapshot("http://target");

        assertTrue(snapshot.contains("\"version\" : 1"));
        assertTrue(snapshot.contains("\"baseUrl\" : \"http://target\""));
        assertTrue(snapshot.contains("\"alertName\" : \"SQL Injection\""));
        assertTrue(snapshot.contains("\"fingerprint\""));
    }

    @Test
    void diffFindingsHighlightsNetNewGroups() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101")
        ));
        String baselineSnapshot = service.exportFindingsSnapshot("http://target");

        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"),
                createAlert("2", "40012", "Cross Site Scripting", "Medium", "High", "http://target/b", "102")
        ));

        String diff = service.diffFindings("http://target", baselineSnapshot, 10);

        assertTrue(diff.contains("New Findings: 1"));
        assertTrue(diff.contains("Resolved Findings: 0"));
        assertTrue(diff.contains("Cross Site Scripting"));
    }

    @Test
    void diffFindingsRejectsInvalidBaselinePayload() {
        assertThrows(IllegalArgumentException.class, () -> service.diffFindings("http://target", "{not-json}", 10));
    }

    @Test
    void findingsRejectGlobalReads() {
        assertThrows(IllegalArgumentException.class, () -> service.getFindingsSummary(null));
    }

    @Test
    void findingsRejectTargetsWithoutVisibleScanEvidence() {
        assertThrows(IllegalArgumentException.class, () -> service.getFindingsSummary("http://other"));
    }

    @Test
    void findingsPostFilterReturnedAlertsWithCanonicalScope() {
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("https://target/app")).thenReturn(true);
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                createAlert("1", "40018", "Allowed", "High", "Medium", "https://target/app/page", "101"),
                createAlert("2", "40018", "Default Port", "High", "Medium", "https://target:443/app/deeper", "102"),
                createAlert("3", "40018", "Path Prefix Bypass", "High", "Medium", "https://target/app2", "103"),
                createAlert("4", "40018", "Host Prefix Bypass", "High", "Medium", "https://target.evil/app", "104"),
                createAlert("5", "40018", "Malformed", "High", "Medium", "not-a-url", "105")
        ));

        String result = service.getAlertInstances("https://target/app", null, null, 10);

        assertTrue(result.contains("Allowed"));
        assertTrue(result.contains("Default Port"));
        assertFalse(result.contains("Path Prefix Bypass"));
        assertFalse(result.contains("Host Prefix Bypass"));
        assertFalse(result.contains("Malformed"));
    }

    private AlertSnapshot createAlert(String id,
                                      String pluginId,
                                      String name,
                                      String risk,
                                      String confidence,
                                      String url,
                                      String messageId) {
        return new AlertSnapshot(
                id,
                pluginId,
                name,
                name + " description",
                risk,
                confidence,
                url,
                "id",
                "attack payload",
                "evidence sample",
                "https://example.com/reference",
                "Apply a fix",
                messageId,
                "89",
                "19"
        );
    }
}
