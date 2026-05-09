package mcp.server.zap.core.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import mcp.server.zap.core.gateway.EngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.extension.api.protection.ReportArtifactBoundary;
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
    void generateReportRejectsBoundaryWriteDirectoryOutsideConfiguredRoot() throws Exception {
        Path reportRoot = Files.createTempDirectory("report-service-root");
        ReflectionTestUtils.setField(service, "reportDirectory", reportRoot.toString());
        service.setReportArtifactBoundary(new ReportArtifactBoundary() {
            @Override
            public Path resolveWriteDirectory(Path defaultDirectory) {
                return Path.of("/");
            }

            @Override
            public Path resolveReadDirectory(Path defaultDirectory) {
                return defaultDirectory;
            }
        });

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.generateReport("traditional-json-plus", "light", "site"));
    }

    @Test
    void readReportRejectsBoundaryReadDirectoryOutsideConfiguredRoot() throws Exception {
        Path reportRoot = Files.createTempDirectory("report-service-root");
        Path siblingRoot = Files.createTempDirectory("report-service-sibling");
        Path siblingReport = siblingRoot.resolve("report.json");
        Files.writeString(siblingReport, "{\"status\":\"bad\"}");
        ReflectionTestUtils.setField(service, "reportDirectory", reportRoot.toString());
        service.setReportArtifactBoundary(new ReportArtifactBoundary() {
            @Override
            public Path resolveWriteDirectory(Path defaultDirectory) {
                return defaultDirectory;
            }

            @Override
            public Path resolveReadDirectory(Path defaultDirectory) {
                return siblingRoot;
            }
        });

        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.readReport(siblingReport.toString(), 1000));
    }

    @Test
    void generateReportCopiesConfiguredRootPermissionsToWorkspaceDirectoryPath() throws Exception {
        Path reportRoot = Files.createTempDirectory("report-service-root-permissions");
        Path workspaceParent = reportRoot.resolve("workspaces");
        Path workspaceDirectory = workspaceParent.resolve("default-workspace");
        Set<PosixFilePermission> rootPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
        );
        try {
            Files.setPosixFilePermissions(reportRoot, rootPermissions);
        } catch (UnsupportedOperationException ignored) {
            return;
        }
        ReflectionTestUtils.setField(service, "reportDirectory", reportRoot.toString());
        when(reportAccess.generateReport(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ReportGenerationRequest request = invocation.getArgument(0, ReportGenerationRequest.class);
                    Path reportDirectory = Path.of(request.reportDirectory());
                    assertEquals(rootPermissions, Files.getPosixFilePermissions(reportDirectory));
                    Path reportPath = reportDirectory.resolve(request.reportFileName() + ".json");
                    Files.writeString(reportPath, "{\"status\":\"ok\"}");
                    return reportPath.toString();
                });

        String result = service.generateReport("traditional-json-plus", "light", "site");

        assertEquals(rootPermissions, Files.getPosixFilePermissions(workspaceParent));
        assertEquals(rootPermissions, Files.getPosixFilePermissions(workspaceDirectory));
        assertTrue(result.startsWith(workspaceDirectory.toString()));
    }

    @Test
    void readReportReturnsFileContents() throws Exception {
        Path reportDir = Files.createTempDirectory("report-service-test");
        Path reportFile = reportDir.resolve("workspaces/default-workspace/sample-report.json");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, "{\"status\":\"ok\"}");
        ReflectionTestUtils.setField(service, "reportDirectory", reportDir.toString());

        String result = service.readReport(reportFile.toString(), 1000);

        assertTrue(result.contains("Report artifact"));
        assertTrue(result.contains("Truncated: no"));
        assertTrue(result.contains("{\"status\":\"ok\"}"));
    }

    @Test
    void readReportRejectsOtherWorkspaceReportPath() throws Exception {
        Path reportDir = Files.createTempDirectory("report-service-test");
        Path otherWorkspaceReport = reportDir.resolve("workspaces/other-workspace/report.json");
        Files.createDirectories(otherWorkspaceReport.getParent());
        Files.writeString(otherWorkspaceReport, "{\"status\":\"other\"}");
        ReflectionTestUtils.setField(service, "reportDirectory", reportDir.toString());

        assertThrowsExactly(IllegalArgumentException.class, () -> service.readReport(otherWorkspaceReport.toString(), 1000));
    }

    @Test
    void readReportRejectsTraversalOutsideReportDirectory() throws Exception {
        Path reportDir = Files.createTempDirectory("report-service-test-root");
        Path outsideFile = Files.createTempFile("report-service-test-outside", ".txt");
        ReflectionTestUtils.setField(service, "reportDirectory", reportDir.toString());

        assertThrowsExactly(IllegalArgumentException.class, () -> service.readReport(outsideFile.toString(), 1000));
    }
}
