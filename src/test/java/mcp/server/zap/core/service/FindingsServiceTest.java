package mcp.server.zap.core.service;

import java.util.List;
import java.util.Map;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(snapshot.contains("\"version\" : 2"));
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
    void changedExampleUrlForSameNodeDoesNotCreateNewOrResolvedFinding() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of())));
        String baseline = service.exportFindingsSnapshot("http://target");

        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=456", "http://target/product (id)", "GET", Map.of())));

        String diff = service.diffFindings("http://target", baseline, 10);
        assertTrue(diff.contains("New Findings: 0"));
        assertTrue(diff.contains("Resolved Findings: 0"));
        assertTrue(diff.contains("Unchanged Findings: 1"));
    }

    @Test
    void differentNodesAndHttpMethodsRemainDistinct() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of())));
        String baseline = service.exportFindingsSnapshot("http://target");

        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of()),
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "POST", Map.of()),
                endpointAlert("http://target/order?id=123", "http://target/order (id)", "GET", Map.of())));

        String diff = service.diffFindings("http://target", baseline, 10);
        assertTrue(diff.contains("New Findings: 2"));
        assertTrue(diff.contains("Resolved Findings: 0"));
        assertTrue(diff.contains("Unchanged Findings: 1"));
    }

    @Test
    void missingNodeNameFallsBackToUrlAndDoesNotAliasNodeIdentity() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", null, "GET", Map.of())));
        String baseline = service.exportFindingsSnapshot("http://target");

        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=456", "  ", "GET", Map.of()),
                endpointAlert("http://target/product?id=123", "http://target/product?id=123", "GET", Map.of())));

        String diff = service.diffFindings("http://target", baseline, 10);
        assertTrue(diff.contains("New Findings: 2"));
        assertTrue(diff.contains("Resolved Findings: 1"));
        assertTrue(diff.contains("Unchanged Findings: 0"));
    }

    @Test
    void comparisonsCountUniqueIdentitiesWhileSnapshotPreservesExampleUrls() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of()),
                endpointAlert("http://target/product?id=456", "http://target/product (id)", "GET", Map.of())));
        String baseline = service.exportFindingsSnapshot("http://target");
        assertEquals(2, new ObjectMapper().readTree(baseline).path("fingerprints").size());

        String diff = service.diffFindings("http://target", baseline, 10);
        assertTrue(diff.contains("Baseline Findings: 1"));
        assertTrue(diff.contains("Current Findings: 1"));
        assertTrue(diff.contains("New Findings: 0"));
        assertTrue(diff.contains("Unchanged Findings: 1"));
    }

    @Test
    void versionOneBaselineRetainsLegacyUrlComparison() {
        String baseline = """
                {"version":1,"baseUrl":"http://target","exportedAt":"2026-01-01T00:00:00Z",
                 "fingerprints":[{"fingerprint":"40018||SQL Injection||High||Medium||http://target/product?id=123||id",
                   "pluginId":"40018","alertName":"SQL Injection","risk":"High","confidence":"Medium",
                   "url":"http://target/product?id=123","param":"id"}]}
                """;
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of())));

        String unchanged = service.diffFindings("http://target", baseline, 10);
        assertTrue(unchanged.contains("Unchanged Findings: 1"));
        assertTrue(unchanged.contains("Legacy version 1 baseline"));

        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=456", "http://target/product (id)", "GET", Map.of())));
        String changed = service.diffFindings("http://target", baseline, 10);
        assertTrue(changed.contains("New Findings: 1"));
        assertTrue(changed.contains("Resolved Findings: 1"));
    }

    @Test
    void metadataSurvivesSnapshotsAndTagChangesDoNotChangeIdentity() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of())));
        String baseline = service.exportFindingsSnapshot("http://target");
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET",
                        Map.of("SYSTEMIC", "", "CWE-89", "https://example.com/cwe"))));

        var snapshot = new ObjectMapper().readTree(service.exportFindingsSnapshot("http://target"));
        var finding = snapshot.path("fingerprints").get(0);
        assertEquals("http://target/product (id)", finding.path("nodeName").asString());
        assertEquals("GET", finding.path("method").asString());
        assertEquals("", finding.path("tags").path("SYSTEMIC").asString());
        assertEquals("https://example.com/cwe", finding.path("tags").path("CWE-89").asString());
        assertTrue(service.diffFindings("http://target", baseline, 10).contains("Unchanged Findings: 1"));
    }

    @Test
    void systemicOutputExplainsRecordedCountsAndRawInstancesExposeMetadata() {
        when(findingAccess.loadAlerts(any())).thenReturn(List.of(
                endpointAlert("http://target/product?id=123", "http://target/product (id)", "GET", Map.of("SYSTEMIC", ""))));

        String summary = service.getFindingsSummary("http://target");
        String details = service.getAlertDetails("http://target", null, null);
        String instances = service.getAlertInstances("http://target", null, null, 10);
        assertTrue(summary.contains("Systemic; recorded alerts: 1; typically site-wide"));
        assertTrue(details.contains("Systemic; recorded alerts: 1; typically site-wide"));
        assertFalse(summary.contains("(1 instances)"));
        assertTrue(instances.contains("Node Name: http://target/product (id)"));
        assertTrue(instances.contains("Method: GET"));
        assertTrue(instances.contains("Tags: {SYSTEMIC=}"));
        assertTrue(instances.contains("do not measure all affected endpoints"));
        String emptyBaseline = "{\"version\":2,\"fingerprints\":[]}";
        assertTrue(service.diffFindings("http://target", emptyBaseline, 10)
                .contains("Systemic; recorded findings: 1"));
    }

    @Test
    void snapshotsRejectUnsupportedVersionsAndMissingFingerprints() {
        for (String payload : List.of("{\"version\":3,\"fingerprints\":[]}",
                "{\"version\":2}", "{\"version\":2,\"fingerprints\":[{}]}")) {
            assertThrows(IllegalArgumentException.class, () -> service.diffFindings("http://target", payload, 10));
        }
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

    private AlertSnapshot endpointAlert(String url, String nodeName, String method, Map<String, String> tags) {
        AlertSnapshot base = createAlert("1", "40018", "SQL Injection", "High", "Medium", url, "101");
        return new AlertSnapshot(base.id(), base.pluginId(), base.name(), base.description(), base.risk(),
                base.confidence(), base.url(), base.param(), base.attack(), base.evidence(), base.reference(),
                base.solution(), base.messageId(), base.cweId(), base.wascId(), nodeName, method, tags);
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
