package mcp.server.zap.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Alert;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FindingsServiceTest {
    private Alert alertApi;
    private FindingsService service;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        alertApi = mock(Alert.class);
        clientApi.alert = alertApi;
        service = new FindingsService(clientApi);
    }

    @Test
    void getAlertDetailsReturnsGroupedSummaryWhenMultipleFamiliesMatch() throws Exception {
        ApiResponseList list = new ApiResponseList("alerts");
        list.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        list.addItem(createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102"));
        list.addItem(createAlert("3", "40012", "Cross Site Scripting", "Medium", "High", "http://target/c", "103"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(list);

        String result = service.getAlertDetails("http://target", null, null);

        assertTrue(result.contains("Alert detail groups: 2"));
        assertTrue(result.contains("SQL Injection"));
        assertTrue(result.contains("Plugin ID: 40018"));
        assertTrue(result.contains("Cross Site Scripting"));
        assertTrue(result.contains("bounded instance view"));
        assertFalse(result.contains("zap_alert_instances"));
    }

    @Test
    void getFindingsSummaryReturnsGroupedMarkdown() throws Exception {
        ApiResponseList list = new ApiResponseList("alerts");
        list.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        list.addItem(createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102"));
        list.addItem(createAlert("3", "40012", "Cross Site Scripting", "Medium", "High", "http://target/c", "103"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(list);

        String markdown = service.getFindingsSummary("http://target");

        assertTrue(markdown.contains("**Total Alerts:** 3"));
        assertTrue(markdown.contains("## 🔴 High Risk"));
        assertTrue(markdown.contains("**SQL Injection** (2 instances)"));
        assertTrue(markdown.contains("## 🔴 Medium Risk"));
    }

    @Test
    void getAlertDetailsReturnsDetailedViewForSingleFamily() throws Exception {
        ApiResponseList list = new ApiResponseList("alerts");
        list.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(list);

        String result = service.getAlertDetails("http://target", "40018", null);

        assertTrue(result.contains("Alert details"));
        assertTrue(result.contains("Alert Name: SQL Injection"));
        assertTrue(result.contains("Instances: 1"));
        assertTrue(result.contains("Sample URL: http://target/a"));
        assertTrue(result.contains("Inspect bounded instances"));
        assertFalse(result.contains("zap_alert_instances"));
    }

    @Test
    void getAlertInstancesReturnsBoundedResults() throws Exception {
        ApiResponseList list = new ApiResponseList("alerts");
        list.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        list.addItem(createAlert("2", "40018", "SQL Injection", "High", "Medium", "http://target/b", "102"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(list);

        String result = service.getAlertInstances("http://target", "40018", null, 1);

        assertTrue(result.contains("Alert instances returned: 1 of 2"));
        assertTrue(result.contains("Alert ID: 1"));
        assertTrue(result.contains("Message ID: 101"));
        assertTrue(result.contains("Results truncated."));
    }

    @Test
    void exportFindingsSnapshotReturnsStableJsonPayload() throws Exception {
        ApiResponseList list = new ApiResponseList("alerts");
        list.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(list);

        String snapshot = service.exportFindingsSnapshot("http://target");

        assertTrue(snapshot.contains("\"version\" : 1"));
        assertTrue(snapshot.contains("\"baseUrl\" : \"http://target\""));
        assertTrue(snapshot.contains("\"alertName\" : \"SQL Injection\""));
        assertTrue(snapshot.contains("\"fingerprint\""));
    }

    @Test
    void diffFindingsHighlightsNetNewGroups() throws Exception {
        ApiResponseList baseline = new ApiResponseList("alerts");
        baseline.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(baseline);
        String baselineSnapshot = service.exportFindingsSnapshot("http://target");

        ApiResponseList current = new ApiResponseList("alerts");
        current.addItem(createAlert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a", "101"));
        current.addItem(createAlert("2", "40012", "Cross Site Scripting", "Medium", "High", "http://target/b", "102"));
        when(alertApi.alerts(anyString(), anyString(), anyString(), isNull(), isNull(), isNull())).thenReturn(current);

        String diff = service.diffFindings("http://target", baselineSnapshot, 10);

        assertTrue(diff.contains("New Findings: 1"));
        assertTrue(diff.contains("Resolved Findings: 0"));
        assertTrue(diff.contains("Cross Site Scripting"));
    }

    @Test
    void diffFindingsRejectsInvalidBaselinePayload() {
        assertThrows(IllegalArgumentException.class, () -> service.diffFindings("http://target", "{not-json}", 10));
    }

    private ApiResponseSet createAlert(String id,
                                       String pluginId,
                                       String name,
                                       String risk,
                                       String confidence,
                                       String url,
                                       String messageId) {
        Map<String, ApiResponse> values = new HashMap<>();
        values.put("id", new ApiResponseElement("id", id));
        values.put("pluginId", new ApiResponseElement("pluginId", pluginId));
        values.put("name", new ApiResponseElement("name", name));
        values.put("description", new ApiResponseElement("description", name + " description"));
        values.put("risk", new ApiResponseElement("risk", risk));
        values.put("confidence", new ApiResponseElement("confidence", confidence));
        values.put("url", new ApiResponseElement("url", url));
        values.put("param", new ApiResponseElement("param", "id"));
        values.put("attack", new ApiResponseElement("attack", "attack payload"));
        values.put("evidence", new ApiResponseElement("evidence", "evidence sample"));
        values.put("reference", new ApiResponseElement("reference", "https://example.com/reference"));
        values.put("solution", new ApiResponseElement("solution", "Apply a fix"));
        values.put("messageId", new ApiResponseElement("messageId", messageId));
        values.put("cweid", new ApiResponseElement("cweid", "89"));
        values.put("wascid", new ApiResponseElement("wascid", "19"));
        return new ApiResponseSet("alert", values);
    }
}
