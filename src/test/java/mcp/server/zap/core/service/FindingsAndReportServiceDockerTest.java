package mcp.server.zap.core.service;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import mcp.server.zap.core.gateway.ZapEngineContextAccess;
import mcp.server.zap.core.gateway.ZapEngineFindingAccess;
import mcp.server.zap.core.gateway.ZapEngineReportAccess;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.junit.jupiter.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("docker")
@Testcontainers
class FindingsAndReportServiceDockerTest {
    private static final Network NETWORK = Network.newNetwork();
    private static final Path REPORT_DIR = createReportDirectory();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("findings-target", "findings-other-target", "findings-target.evil")
                    .withCopyToContainer(Transferable.of("""
                            server {
                                listen 80;
                                listen 8080;
                                root /usr/share/nginx/html;
                            }
                            """), "/etc/nginx/conf.d/default.conf")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(ZapDockerTestSupport.zapImage())
                    .withNetwork(NETWORK)
                    .dependsOn(TARGET)
                    .withExposedPorts(8090)
                    .withCreateContainerCmdModifier(cmd -> addHostBind(cmd, REPORT_DIR))
                    .withCommand(
                            "zap.sh",
                            "-daemon",
                            "-host",
                            "0.0.0.0",
                            "-port",
                            "8090",
                            "-config",
                            "api.disablekey=true",
                            "-config",
                            "api.addrs.addr.name=.*",
                            "-config",
                            "api.addrs.addr.regex=true"
                    )
                    .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static ClientApi clientApi;
    private static FindingsService findingsService;
    private static ReportService reportService;
    private static ZapEngineReportAccess reportAccess;

    @BeforeAll
    static void setupServices() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);

        findingsService = new FindingsService(new ZapEngineFindingAccess(clientApi));
        ScanHistoryLedgerService scanHistoryLedgerService = mock(ScanHistoryLedgerService.class);
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("http://findings-target/")).thenReturn(true);
        findingsService.setScanHistoryLedgerService(scanHistoryLedgerService);
        reportAccess = new ZapEngineReportAccess(clientApi);
        reportService = new ReportService(reportAccess);
        ReflectionTestUtils.setField(reportService, "reportDirectory", REPORT_DIR.toString());
    }

    @Test
    void alertDetailsInstancesAndReportReadWorkAgainstRealZap() throws Exception {
        String targetUrl = "http://findings-target/";
        clientApi.core.accessUrl(targetUrl, "true");

        String messageId = awaitFirstMessageId(targetUrl);
        clientApi.alert.addAlert(
                messageId,
                "Codex Test Alert",
                "2",
                "2",
                "Synthetic alert for integration testing",
                "id",
                "1' OR '1'='1",
                "Added by smoke test",
                "Apply a fix",
                "https://example.com/reference",
                "alert evidence",
                "89",
                "19"
        );

        String details = findingsService.getAlertDetails(targetUrl, null, "Codex Test Alert");
        String instances = findingsService.getAlertInstances(targetUrl, null, "Codex Test Alert", 10);

        assertTrue(details.contains("Codex Test Alert"));
        assertTrue(instances.contains("Message ID: " + messageId));

        var alert = new ZapEngineFindingAccess(clientApi).loadAlerts(targetUrl).stream()
                .filter(finding -> "Codex Test Alert".equals(finding.name()))
                .findFirst().orElseThrow();
        assertEquals("GET", alert.method());
        assertFalse(alert.nodeName() == null || alert.nodeName().isBlank());
        assertTrue(instances.contains("Node Name: " + alert.nodeName()));
        assertTrue(instances.contains("Method: GET"));
        String snapshot = findingsService.exportFindingsSnapshot(targetUrl);
        assertTrue(snapshot.contains("\"version\" : 2"));

        String reportTemplate = awaitReportTemplate();
        String reportSite = awaitReportSite(targetUrl);
        String reportPath = reportService.generateReport(reportTemplate, "light", reportSite);
        awaitReportExists(Path.of(reportPath));
        String reportContents = reportService.readReport(reportPath, 50000);

        assertTrue(reportContents.contains("Report artifact"));
        assertTrue(reportContents.contains("Codex Test Alert"));
    }

    @Test
    void targetReportsOmitOtherSiteAndSessionMetadataAgainstRealZap() throws Exception {
        String selected = "http://findings-target/";
        String other = "http://findings-other-target/";
        clientApi.stats.setOptionInMemoryEnabled(true);
        addFixtureAlert(selected, "Selected Target Fixture Alert");
        addFixtureAlert(other, "Other Target Private Fixture Alert");

        // Positive control: native JSON-plus adds insights from the whole ZAP session,
        // even when its alert site filter selects only the first local fixture origin.
        String nativePath = reportAccess.generateReport(new ReportGenerationRequest(
                "Native scope control", "traditional-json-plus", "", "", "", selected,
                "", "", "", "native-scope-control", "", REPORT_DIR.toString(), "false"));
        JsonMapper json = JsonMapper.builder().build();
        JsonNode nativeReport = json.readTree(Path.of(nativePath));
        assertTrue(nativeReport.path("insights").valueStream()
                .anyMatch(insight -> other.substring(0, other.length() - 1).equals(insight.path("site").asString())),
                "The native control should contain the unrelated site's engine insights");

        String scopedPath = reportService.generateReport("traditional-json-plus", "light", "HTTP://FINDINGS-TARGET:80");
        JsonNode report = json.readTree(Path.of(scopedPath));
        assertEquals(Set.of("@programName", "@version", "@generated", "created", "site"),
                Set.copyOf(report.propertyNames()));
        assertEquals(1, report.path("site").size());
        assertEquals(selected, report.path("site").get(0).path("@name").asString());
        String serialized = json.writeValueAsString(report);
        assertTrue(serialized.contains("Selected Target Fixture Alert"));
        assertFalse(serialized.contains("Other Target Private Fixture Alert"));
        assertFalse(serialized.contains("findings-other-target"));
        JsonNode selectedInstance = report.path("site").get(0).path("alerts").valueStream()
                .filter(alert -> "Selected Target Fixture Alert".equals(alert.path("name").asString()))
                .findFirst().orElseThrow().path("instances").get(0);
        assertTrue(selectedInstance.path("request-header").asString().contains("findings-target"));
        assertFalse(selectedInstance.path("response-body").asString().isBlank());

        for (String template : List.of("traditional-html-plus", "traditional-md")) {
            String text = Files.readString(Path.of(reportService.generateReport(template, "light", selected)));
            assertTrue(text.contains("Selected Target Fixture Alert"));
            assertFalse(text.contains("Other Target Private Fixture Alert"));
            assertFalse(text.contains("findings-other-target"));
            assertFalse(text.contains("Number of Sites tree nodes actively scanned"));
        }
        try (var staging = Files.list(REPORT_DIR.resolve(".report-staging"))) {
            assertEquals(0, staging.count());
        }

        JsonNode combined = json.readTree(Path.of(reportService.generateReport(
                "traditional-json", "", selected + "," + other)));
        assertEquals(Set.of(selected, other), combined.path("site").valueStream()
                .map(site -> site.path("@name").asString()).collect(java.util.stream.Collectors.toSet()));
        String combinedText = json.writeValueAsString(combined);
        assertTrue(combinedText.contains("Selected Target Fixture Alert"));
        assertTrue(combinedText.contains("Other Target Private Fixture Alert"));
    }

    @Test
    void scopedReportsKeepBothRootFormsWithoutBroadeningHostPortOrPathSelection() throws Exception {
        String origin = "http://findings-target";
        String otherOrigin = "http://findings-other-target";
        addFixtureAlert(origin, "Bare Root Completeness Fixture");
        addFixtureAlert(origin + "/", "Slash Root Completeness Fixture");
        addFixtureAlert(origin + "/app/page", "Selected App Completeness Fixture");
        addFixtureAlert(origin + "/other/page", "Sibling Path Completeness Fixture");
        addFixtureAlert(origin + "/App/page", "Different Case Path Completeness Fixture");
        addFixtureAlert(origin + ".evil/", "Lookalike Host Private Fixture");
        addFixtureAlert(origin + ":8080/", "Sibling Port Private Fixture");
        addFixtureAlert(otherOrigin + "/app/page", "Other Origin Selected App Fixture");
        addFixtureAlert(otherOrigin + "/other/page", "Other Origin Sibling Path Private Fixture");

        // Check the live engine fixture before testing report generation: a normalized
        // fixture would hide the root-URI omission this regression is meant to expose.
        var findings = new ZapEngineFindingAccess(clientApi).loadAlerts(null);
        assertEquals(origin, findings.stream()
                .filter(alert -> "Bare Root Completeness Fixture".equals(alert.name()))
                .findFirst().orElseThrow().url());
        assertEquals(origin + "/", findings.stream()
                .filter(alert -> "Slash Root Completeness Fixture".equals(alert.name()))
                .findFirst().orElseThrow().url());

        String nativePath = reportAccess.generateReport(new ReportGenerationRequest(
                "Native root omission control", "traditional-json", "", "", "", origin + "/",
                "", "", "", "native-root-omission-control", "", REPORT_DIR.toString(), "false"));
        String nativeReport = Files.readString(Path.of(nativePath));
        assertTrue(nativeReport.contains("Slash Root Completeness Fixture"));
        assertFalse(nativeReport.contains("Bare Root Completeness Fixture"),
                "The native slash prefix should reproduce the omitted bare-root finding");

        String contextName = "Preserved report fixture context";
        clientApi.context.newContext(contextName);
        clientApi.context.includeInContext(contextName, "http://findings-target(?:/.*)?");
        clientApi.context.setContextInScope(contextName, "true");
        Set<String> contextsBefore = responseValues(clientApi.context.contextList());
        ZapEngineContextAccess contextAccess = new ZapEngineContextAccess(clientApi);
        var contextSettingsBefore = Set.copyOf(contextAccess.listContexts());
        Set<String> contextUrlsBefore = responseValues(clientApi.context.urls(contextName));

        try {
            for (String template : List.of("traditional-json", "traditional-json-plus",
                    "traditional-html", "traditional-html-plus", "traditional-md")) {
                for (String scope : List.of(origin, origin + "/")) {
                    String report = generatedReport(template, scope);
                    assertTrue(report.contains("Bare Root Completeness Fixture"), template + " must keep bare roots");
                    assertTrue(report.contains("Slash Root Completeness Fixture"), template + " must keep slash roots");
                    assertTrue(report.contains("Selected App Completeness Fixture"));
                    assertTrue(report.contains("Sibling Path Completeness Fixture"));
                    assertFalse(report.contains("Lookalike Host Private Fixture"));
                    assertFalse(report.contains("Sibling Port Private Fixture"));
                    assertFalse(report.contains("Other Origin Selected App Fixture"));
                    assertFalse(report.contains("Other Origin Sibling Path Private Fixture"));
                    if (template.contains("json")) {
                        JsonNode site = JsonMapper.builder().build().readTree(report).path("site").get(0);
                        assertEquals(origin + "/", site.path("@name").asString());
                        Set<String> rootUris = site.path("alerts").valueStream()
                                .filter(alert -> alert.path("name").asString().contains("Root Completeness Fixture"))
                                .flatMap(alert -> alert.path("instances").valueStream())
                                .map(instance -> instance.path("uri").asString()).collect(Collectors.toSet());
                        assertEquals(Set.of(origin, origin + "/"), rootUris);
                    }
                }

                String pathReport = generatedReport(template, origin + "/app/");
                assertTrue(pathReport.contains("Selected App Completeness Fixture"));
                assertFalse(pathReport.contains("Bare Root Completeness Fixture"));
                assertFalse(pathReport.contains("Slash Root Completeness Fixture"));
                assertFalse(pathReport.contains("Sibling Path Completeness Fixture"));
                assertFalse(pathReport.contains("Different Case Path Completeness Fixture"));
                assertFalse(pathReport.contains("Other Origin Selected App Fixture"));
                assertFalse(pathReport.contains("Lookalike Host Private Fixture"));
                assertFalse(pathReport.contains("Sibling Port Private Fixture"));

                String combinedReport = generatedReport(template, origin + "/|" + otherOrigin + "/app/");
                assertTrue(combinedReport.contains("Bare Root Completeness Fixture"));
                assertTrue(combinedReport.contains("Slash Root Completeness Fixture"));
                assertTrue(combinedReport.contains("Selected App Completeness Fixture"));
                assertTrue(combinedReport.contains("Other Origin Selected App Fixture"));
                assertFalse(combinedReport.contains("Other Origin Sibling Path Private Fixture"));
                assertFalse(combinedReport.contains("Lookalike Host Private Fixture"));
                assertFalse(combinedReport.contains("Sibling Port Private Fixture"));

                assertEquals(contextsBefore, responseValues(clientApi.context.contextList()),
                        "Report generation must remove its temporary context");
                assertEquals(contextSettingsBefore, Set.copyOf(contextAccess.listContexts()),
                        "Report generation must preserve context IDs, scope flags and include/exclude regexes");
                assertEquals(contextUrlsBefore, responseValues(clientApi.context.urls(contextName)));
            }
        } finally {
            clientApi.context.removeContext(contextName);
        }
    }

    private static String generatedReport(String template, String scope) throws Exception {
        return Files.readString(Path.of(reportService.generateReport(template, "light", scope)));
    }

    private static Set<String> responseValues(ApiResponse response) {
        if (!(response instanceof ApiResponseList list)) {
            throw new IllegalStateException("Expected a list response from the live fixture engine");
        }
        return list.getItems().stream().map(FindingsAndReportServiceDockerTest::apiResponseValue)
                .collect(Collectors.toSet());
    }

    private static void addFixtureAlert(String url, String name) throws Exception {
        ApiResponse response = clientApi.core.accessUrl(url, "false");
        if (!(response instanceof ApiResponseList list) || list.getItems().size() != 1
                || !(list.getItems().getFirst() instanceof ApiResponseSet message)) {
            throw new IllegalStateException("Expected the exact accessed fixture message for " + url);
        }
        clientApi.alert.addAlert(message.getStringValue("id"), name, "2", "2",
                "Synthetic local report scope fixture", "", "", "", "Apply fixture fix",
                "", "fixture evidence", "89", "19");
    }

    private static String awaitFirstMessageId(String baseUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ApiResponse response = clientApi.core.messages(baseUrl, "0", "10");
            if (response instanceof ApiResponseList list && !list.getItems().isEmpty()) {
                ApiResponse first = list.getItems().getFirst();
                if (first instanceof ApiResponseSet set) {
                    String messageId = set.getStringValue("id");
                    if (messageId != null && !messageId.isBlank()) {
                        return messageId;
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("No HTTP messages found for base URL " + baseUrl);
    }

    private static String awaitReportTemplate() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> lastTemplates = List.of();
        while (System.nanoTime() < deadline) {
            lastTemplates = reportAccess.listReportTemplates();
            if (lastTemplates.contains("traditional-json-plus")) {
                return "traditional-json-plus";
            }
            String jsonTemplate = firstTemplateContaining(lastTemplates, "json");
            if (jsonTemplate != null) {
                return jsonTemplate;
            }
            String anyTemplate = firstTemplateContaining(lastTemplates, "");
            if (anyTemplate != null) {
                return anyTemplate;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("No report templates available from ZAP: " + lastTemplates);
    }

    private static String awaitReportSite(String targetUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String normalizedTargetUrl = stripTrailingSlash(targetUrl);
        List<String> lastSites = List.of();
        while (System.nanoTime() < deadline) {
            ApiResponse response = clientApi.core.sites();
            if (response instanceof ApiResponseList list) {
                lastSites = list.getItems().stream()
                        .map(FindingsAndReportServiceDockerTest::apiResponseValue)
                        .toList();
                for (String site : lastSites) {
                    if (normalizedTargetUrl.equals(stripTrailingSlash(site))) {
                        return site;
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("No matching ZAP report site for " + targetUrl + ": " + lastSites);
    }

    private static String apiResponseValue(ApiResponse item) {
        if (item instanceof ApiResponseElement element) {
            return element.getValue();
        }
        return item.toString();
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String firstTemplateContaining(List<String> templates, String value) {
        return templates.stream()
                .filter(template -> template != null && template.contains(value))
                .findFirst()
                .orElse(null);
    }

    private static void awaitReportExists(Path reportPath) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(reportPath)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Report was not generated at " + reportPath);
    }

    private static Path createReportDirectory() {
        try {
            return makeZapAccessible(Files.createTempDirectory("zap-report-smoke"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create report directory", e);
        }
    }

    private static Path makeZapAccessible(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            boolean readable = directory.toFile().setReadable(true, false);
            boolean writable = directory.toFile().setWritable(true, false);
            boolean executable = directory.toFile().setExecutable(true, false);
            if (!readable || !writable || !executable) {
                fail("Unable to make report directory accessible to ZAP");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to make report directory accessible to ZAP", e);
        }
        return directory;
    }

    private static void addHostBind(CreateContainerCmd cmd, Path directory) {
        HostConfig hostConfig = cmd.getHostConfig();
        if (hostConfig == null) {
            hostConfig = new HostConfig();
            cmd.withHostConfig(hostConfig);
        }
        hostConfig.withBinds(new Bind(directory.toString(), new Volume(directory.toString())));
    }
}
