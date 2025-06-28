package mcp.server.zap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
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
    void getHtmlReportReturnsPath() throws Exception {
        when(reports.generate(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("file", "/tmp/report.html"));
        String result = service.getHtmlReport("modern", "light", "site");
        assertTrue(result.endsWith("report.html"));
    }
}