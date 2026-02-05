package mcp.server.zap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Reports;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReportServiceTest {
    private Reports reports;
    private ReportService service;
    private Core core;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        reports = mock(Reports.class);
        core = mock(Core.class);
        clientApi.reports = reports;
        clientApi.core = core;
        service = new ReportService(clientApi);
        ReflectionTestUtils.setField(service, "reportDirectory", "/tmp");
    }

    @Test
    void viewTemplatesReturnsList() throws Exception {
        ApiResponseList list = new ApiResponseList("templates", new ApiResponseElement[]{
                new ApiResponseElement("t", "template1"),
                new ApiResponseElement("t", "template2")});
        when(reports.templates()).thenReturn(list);
        String result = service.viewTemplates();
        assertEquals("template1\ntemplate2", result);
    }

    @Test
    void getHtmlReportReturnsPath() throws Exception {
        when(reports.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("file", "/tmp/report.html"));
        String result = service.getHtmlReport("modern", "light", "site");
        assertTrue(result.endsWith("report.html"));
    }

    @Test
    void getFindingsSummary_ShouldGenerateMarkdown() throws ClientApiException {
        // 1. Mock the ZAP API Response List
        ApiResponseList mockList = new ApiResponseList("alerts");

        // Helper to create alert objects easily
        mockList.addItem(createAlert("SQL Injection", "High", "SQL Injection allows attackers to..."));
        mockList.addItem(createAlert("SQL Injection", "High", "SQL Injection allows...")); // Duplicate type
        mockList.addItem(createAlert("XSS", "Medium", "Cross Site Scripting..."));

        // 2. Configure the Mock Behavior
        when(core.alerts(anyString(), anyString(), anyString())).thenReturn(mockList);

        // 3. Execute
        String markdown = service.getFindingsSummary("http://target.com");

        // 4. Verify output contains key summary elements
        System.out.println(markdown); // Debug print

        assertTrue(markdown.contains("**Total Alerts:** 3"), "Should count total alerts");
        assertTrue(markdown.contains("## 🔴 High Risk"), "Should contain High Risk section");
        assertTrue(markdown.contains("**SQL Injection** (2 instances)"), "Should aggregate duplicates");
        assertTrue(markdown.contains("## 🔴 Medium Risk"), "Should contain Medium Risk section");
    }

    // Helper method to construct ZAP API objects cleanly
    private ApiResponseSet createAlert(String name, String risk, String description) {
        // FIX: The values map must contain ApiResponse objects, not Strings.
        Map<String, ApiResponse> values = new HashMap<>();

        values.put("alert", new ApiResponseElement("alert", name));
        values.put("risk", new ApiResponseElement("risk", risk));
        values.put("description", new ApiResponseElement("description", description));

        // Now use the constructor that accepts Map<String, ApiResponse>
        return new ApiResponseSet("alert", values);
    }
}