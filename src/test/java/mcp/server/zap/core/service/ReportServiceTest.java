package mcp.server.zap.core.service;

import java.nio.file.Files;
import java.nio.file.Path;
import mcp.server.zap.core.gateway.EngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.service.protection.ReportArtifactBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReportServiceTest {
    private EngineReportAccess reportAccess;
    private ReportService service;

    @BeforeEach
    void setup() {
        reportAccess = mock(EngineReportAccess.class);
        service = new ReportService(reportAccess);
        ReflectionTestUtils.setField(service, "reportDirectory", "/tmp");
    }

    @Test
    void viewTemplatesReturnsList() {
        when(reportAccess.listReportTemplates()).thenReturn(java.util.List.of("template1", "template2"));

        String result = service.viewTemplates();

        assertEquals("template1\ntemplate2", result);
    }

    @Test
    void generateReportReturnsPath() {
        when(reportAccess.generateReport(org.mockito.ArgumentMatchers.any()))
                .thenReturn("/tmp/report.html");

        String result = service.generateReport("modern", "light", "site");

        assertTrue(result.endsWith("report.html"));
    }

    @Test
    void generateReportOmitsThemeForJsonTemplates() {
        when(reportAccess.generateReport(org.mockito.ArgumentMatchers.any()))
                .thenReturn("/tmp/report.json");

        String result = service.generateReport("traditional-json-plus", "light", "site");

        assertTrue(result.endsWith("report.json"));
        ArgumentCaptor<ReportGenerationRequest> captor = ArgumentCaptor.forClass(ReportGenerationRequest.class);
        verify(reportAccess).generateReport(captor.capture());
        assertEquals("traditional-json-plus", captor.getValue().template());
        assertEquals("", captor.getValue().theme());
    }

    @Test
    void generateReportUsesBoundaryScopedDirectory() throws Exception {
        Path reportRoot = Files.createTempDirectory("report-service-root");
        Path scopedRoot = reportRoot.resolve("tenants/tenant-alpha/workspaces/workspace-one");
        ReflectionTestUtils.setField(service, "reportDirectory", reportRoot.toString());
        service.setReportArtifactBoundary(new ReportArtifactBoundary() {
            @Override
            public Path resolveWriteDirectory(Path defaultDirectory) {
                return scopedRoot;
            }

            @Override
            public Path resolveReadDirectory(Path defaultDirectory) {
                return scopedRoot;
            }
        });

        when(reportAccess.generateReport(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ReportGenerationRequest request = invocation.getArgument(0, ReportGenerationRequest.class);
                    Path reportPath = Path.of(request.reportDirectory()).resolve(request.reportFileName() + ".json");
                    Files.createDirectories(reportPath.getParent());
                    Files.writeString(reportPath, "{\"tenant\":\"tenant-alpha\"}");
                    return reportPath.toString();
                });

        String result = service.generateReport("traditional-json-plus", "light", "site");

        assertTrue(result.startsWith(scopedRoot.toString()));
        assertTrue(Files.exists(Path.of(result)));
        ArgumentCaptor<ReportGenerationRequest> captor = ArgumentCaptor.forClass(ReportGenerationRequest.class);
        verify(reportAccess).generateReport(captor.capture());
        assertEquals(scopedRoot.toString(), captor.getValue().reportDirectory());
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
