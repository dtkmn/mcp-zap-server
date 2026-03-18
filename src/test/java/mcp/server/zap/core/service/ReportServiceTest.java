package mcp.server.zap.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Reports;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReportServiceTest {
    private Reports reports;
    private ReportService service;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        reports = mock(Reports.class);
        clientApi.reports = reports;
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
    void generateReportReturnsPath() throws Exception {
        when(reports.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("file", "/tmp/report.html"));
        String result = service.generateReport("modern", "light", "site");
        assertTrue(result.endsWith("report.html"));
    }

    @Test
    void generateReportOmitsThemeForJsonTemplates() throws Exception {
        when(reports.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("file", "/tmp/report.json"));

        String result = service.generateReport("traditional-json-plus", "light", "site");

        assertTrue(result.endsWith("report.json"));
        verify(reports).generate(
                any(),
                eq("traditional-json-plus"),
                eq(""),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void readReportReturnsFileContents() throws Exception {
        Path reportDir = Files.createTempDirectory("report-service-test");
        Path reportFile = reportDir.resolve("sample-report.json");
        Files.writeString(reportFile, "{\"status\":\"ok\"}");
        ReflectionTestUtils.setField(service, "reportDirectory", reportDir.toString());

        String result = service.readReport(reportFile.toString(), 1000);

        assertTrue(result.contains("Report artifact"));
        assertTrue(result.contains("Truncated: no"));
        assertTrue(result.contains("{\"status\":\"ok\"}"));
    }

    @Test
    void readReportRejectsTraversalOutsideReportDirectory() throws Exception {
        Path reportDir = Files.createTempDirectory("report-service-test-root");
        Path outsideFile = Files.createTempFile("report-service-test-outside", ".txt");
        ReflectionTestUtils.setField(service, "reportDirectory", reportDir.toString());

        assertThrowsExactly(IllegalArgumentException.class, () -> service.readReport(outsideFile.toString(), 1000));
    }
}
