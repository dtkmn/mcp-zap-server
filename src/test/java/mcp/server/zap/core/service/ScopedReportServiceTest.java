package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.extension.api.protection.ReportArtifactBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScopedReportServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TARGET = "https://selected.example/";
    @TempDir
    Path root;
    private EngineReportAccess engine;
    private ScanHistoryLedgerService ledger;
    private ReportService service;

    @BeforeEach
    void setup() {
        engine = mock(EngineReportAccess.class);
        ledger = mock(ScanHistoryLedgerService.class);
        service = new ReportService(engine);
        service.setScanHistoryLedgerService(ledger);
        ReflectionTestUtils.setField(service, "reportDirectory", root.toString());
    }

    @Test
    void scopedJsonDropsSessionMetadataAndKeepsSelectedAlertEvidence() throws Exception {
        writeReport("json", scopedJson(TARGET, TARGET + "page?token=example"));
        String path = service.generateReport("traditional-json-plus", "light", "https://selected.example");
        JsonNode report = JSON.readTree(Path.of(path));

        assertFalse(report.toString().contains("private"), "Session diagnostics and unknown metadata must be omitted");
        JsonNode site = report.path("site").get(0);
        assertEquals(TARGET, site.path("@name").asString());
        assertEquals("selected.example", site.path("@host").asString());
        assertEquals("443", site.path("@port").asString());
        assertFalse(site.has("statistics"));
        JsonNode instance = site.path("alerts").get(0).path("instances").get(0);
        assertEquals("request body 😀", instance.path("request-body").asString());
        assertEquals("response body with https://embedded.example/ link", instance.path("response-body").asString());
        assertEquals("evidence", instance.path("evidence").asString());
        assertStagingEmpty();
        verify(ledger).recordReportArtifact(eq(path), eq("traditional-json-plus"), eq(TARGET), any());
    }

    @Test
    void explicitFullSessionReportKeepsEngineMetadata() throws Exception {
        writeReport("json", scopedJson(TARGET, TARGET));
        String path = service.generateReport("traditional-json-plus", "light", " ");
        assertTrue(JSON.readTree(Path.of(path)).has("insights"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://selected.example.evil/page", "http://selected.example/page",
            "https://selected.example:8443/page", "https://other.example/page"})
    void rejectsAlertInstancesOutsideTheExactOrigin(String uri) throws Exception {
        writeReport("json", scopedJson(TARGET, uri));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateReport("traditional-json-plus", "", TARGET));
        assertStagingEmpty();
        assertNoPublishedArtifacts();
        verifyNoInteractions(ledger);
    }

    @Test
    void rejectsInstanceOutsideRequestedPathPrefix() throws Exception {
        writeReport("json", scopedJson(TARGET + "app/", TARGET + "other/"));
        assertThrows(IllegalArgumentException.class,
                () -> service.generateReport("traditional-json-plus", "", TARGET + "app/"));
        assertStagingEmpty();
        verifyNoInteractions(ledger);
    }

    @Test
    void acceptsEquivalentDefaultPortAndDocumentedRawPathPrefix() throws Exception {
        writeReport("json", scopedJson(TARGET + "app", "https://SELECTED.example:443/application?q=1"));
        String path = service.generateReport("traditional-json-plus", "", TARGET + "app");
        assertTrue(Files.readString(Path.of(path)).contains("application?q=1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://selected.example", "HTTPS://SELECTED.EXAMPLE:443"})
    void acceptsNativeRootSiteAndInstanceWithoutTrailingSlash(String siteName) throws Exception {
        writeReport("json", scopedJson(siteName, siteName + "?q=1"));
        String path = service.generateReport("traditional-json-plus", "", TARGET);
        JsonNode site = JSON.readTree(Path.of(path)).path("site").get(0);
        assertEquals(TARGET, site.path("@name").asString());
        assertEquals(siteName + "?q=1", site.path("alerts").get(0).path("instances").get(0).path("uri").asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://selected.example/?a=1", "https://selected.example/#fragment",
            "https://user:secret@selected.example/", "selected.example", "file:///tmp/report",
            "https://selected.example/|", "https://selected.example:70000/"})
    void rejectsUnrepresentableScopesBeforeDispatch(String scope) {
        assertThrows(IllegalArgumentException.class, () -> service.generateReport("traditional-json-plus", "", scope));
        verifyNoInteractions(engine, ledger);
    }


    @Test
    void duplicateOrMissingRequestedSitesCannotPublishAMisleadingReport() throws Exception {
        writeReport("json", "{\"site\":[{\"@name\":\"https://selected.example/\",\"alerts\":[]},"
                + "{\"@name\":\"https://selected.example/\",\"alerts\":[]}]}");
        assertThrows(IllegalArgumentException.class, () -> service.generateReport("traditional-json", "",
                "https://selected.example/|https://second.example/"));
        assertStagingEmpty();
        writeReport("json", "{\"site\":[{\"@name\":\"https://selected.example/\",\"alerts\":[]}]}");
        assertThrows(IllegalArgumentException.class, () -> service.generateReport("traditional-json", "",
                "https://selected.example/|https://second.example/"));
        assertStagingEmpty();
        verifyNoInteractions(ledger);
    }

    @Test
    void symlinkWorkspaceIsRejectedBeforeCreatingDirectoriesOrDispatchingToEngine() throws Exception {
        Path outside = Files.createTempDirectory("report-generation-outside");
        Files.createDirectories(root.resolve("workspaces"));
        Files.createSymbolicLink(root.resolve("workspaces/default-workspace"), outside);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> service.generateReport("traditional-json-plus", "", TARGET));
            verifyNoInteractions(engine, ledger);
            try (Stream<Path> files = Files.list(outside)) {
                assertEquals(0, files.count());
            }
        } finally {
            Files.delete(outside);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"modern", "custom-template", "sarif-json", "traditional-xml-plus"})
    void unknownTemplateCannotPretendToHaveScopedMetadata(String template) {
        assertThrows(IllegalArgumentException.class, () -> service.generateReport(template, "", TARGET));
        verifyNoInteractions(engine, ledger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"site\":[]}", "{\"site\":null}", "{\"site\":[],\"site\":[]}",
            "{\"site\":[{\"@name\":\"https://selected.example/\"}]}",
            "{\"site\":[{\"@name\":\"https://selected.example/\",\"alerts\":[{\"instances\":[{}]}]}]}",
            "{\"site\":[{\"@name\":\"https://other.example/\",\"alerts\":[]}]}",
            "{\"site\":[{\"@name\":\"https://selected.example/\",\"alerts\":[]}]} {}"})
    void malformedArtifactsAreNeverPublishedOrRegistered(String raw) throws Exception {
        writeReport("json", raw);
        assertThrows(RuntimeException.class, () -> service.generateReport("traditional-json-plus", "", TARGET));
        assertStagingEmpty();
        assertNoPublishedArtifacts();
        verifyNoInteractions(ledger);
    }

    @Test
    void rejectsOversizedRawArtifactAndRemovesIt() throws Exception {
        when(engine.generateReport(any())).thenAnswer(invocation -> {
            ReportGenerationRequest request = invocation.getArgument(0);
            Path file = Path.of(request.reportDirectory()).resolve(request.reportFileName() + ".json");
            try (var output = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                output.position(ReportService.MAX_REPORT_ARTIFACT_BYTES);
                output.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
            }
            return file.toString();
        });
        assertThrows(IllegalArgumentException.class, () -> service.generateReport("traditional-json-plus", "", TARGET));
        assertStagingEmpty();
        verifyNoInteractions(ledger);
    }

    @Test
    void failureInEngineGenerationRemovesUnpublishedRawArtifact() throws Exception {
        when(engine.generateReport(any())).thenAnswer(invocation -> {
            ReportGenerationRequest request = invocation.getArgument(0);
            Files.writeString(Path.of(request.reportDirectory()).resolve("partial.json"), "private session data");
            throw new ZapApiException("Engine generation failed", new IOException("fixture generation failure"));
        });
        assertThrows(ZapApiException.class, () -> service.generateReport("traditional-json-plus", "", TARGET));
        assertStagingEmpty();
        verifyNoInteractions(ledger);
    }

    @Test
    void broadBoundaryCannotReadRawGenerationStaging() throws Exception {
        service.setReportArtifactBoundary(new ReportArtifactBoundary() {
            public Path resolveWriteDirectory(Path defaultDirectory) { return defaultDirectory; }
            public Path resolveReadDirectory(Path defaultDirectory) { return defaultDirectory; }
        });
        when(engine.generateReport(any())).thenAnswer(invocation -> {
            ReportGenerationRequest request = invocation.getArgument(0);
            Path raw = Path.of(request.reportDirectory()).resolve(request.reportFileName() + ".json");
            Files.writeString(raw, scopedJson(TARGET, TARGET));
            assertThrows(IllegalArgumentException.class, () -> service.readReport(raw.toString(), 1000));
            assertThrows(IllegalArgumentException.class, () -> service.readReportChunk(raw.toString(), 0L, 1000, null));
            return raw.toString();
        });
        service.generateReport("traditional-json-plus", "", TARGET);
        assertStagingEmpty();
    }

    private void writeReport(String extension, String content) {
        doAnswer(invocation -> {
            ReportGenerationRequest request = invocation.getArgument(0);
            Path file = Path.of(request.reportDirectory()).resolve(request.reportFileName() + "." + extension);
            Files.writeString(file, content);
            return file.toString();
        }).when(engine).generateReport(any());
    }

    private void assertStagingEmpty() throws IOException {
        Path staging = root.resolve(".report-staging");
        if (Files.exists(staging)) {
            try (Stream<Path> files = Files.list(staging)) {
                assertEquals(0, files.count());
            }
        }
    }

    private void assertNoPublishedArtifacts() throws IOException {
        Path workspace = root.resolve("workspaces/default-workspace");
        if (Files.exists(workspace)) {
            try (Stream<Path> files = Files.list(workspace)) {
                assertEquals(0, files.count());
            }
        }
    }

    private static String scopedJson(String site, String instance) {
        return """
                {"@programName":"ZAP","@version":"2.17.0","@generated":"today","created":"2026-10-06",
                 "insights":[{"site":"https://other.example/","statistic":999}],
                 "stoppingInsight":{"site":"https://other.example/"},"statistics":{"session":123},
                 "sequences":[{"private":"sequence"}],"afPlanErrors":["private plan"],"afPlanWarns":[],
                 "scriptDiagnostics":{"private":"script output"},"futureSessionMetadata":{"private":"value"},
                 "site":[{"@name":"%s","@host":"ignored-engine-host","@port":"1","@ssl":"false",
                          "statistics":{"session":999},"futureSiteMetadata":{"private":"value"},
                          "alerts":[{"name":"Selected Alert","count":"1","instances":[
                            {"uri":"%s","evidence":"evidence","request-body":"request body 😀",
                             "response-body":"response body with https://embedded.example/ link"}]}]}]}
                """.formatted(site, instance);
    }
}
