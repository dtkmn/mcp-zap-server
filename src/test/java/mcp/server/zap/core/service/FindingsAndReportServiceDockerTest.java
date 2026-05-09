package mcp.server.zap.core.service;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import mcp.server.zap.core.gateway.ZapEngineFindingAccess;
import mcp.server.zap.core.gateway.ZapEngineReportAccess;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
public class FindingsAndReportServiceDockerTest {
    private static final Network NETWORK = Network.newNetwork();
    private static final Path REPORT_DIR = createReportDirectory();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("findings-target")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(DockerImageName.parse("zaproxy/zap-stable:2.17.0"))
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
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static ClientApi clientApi;
    private static FindingsService findingsService;
    private static ReportService reportService;
    private static ZapEngineReportAccess reportAccess;

    @BeforeAll
    static void setupServices() throws Exception {
        clientApi = new ClientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        awaitApiReady();

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

        String reportTemplate = awaitReportTemplate();
        String reportSite = awaitReportSite(targetUrl);
        String reportPath = reportService.generateReport(reportTemplate, "light", reportSite);
        awaitReportExists(Path.of(reportPath));
        String reportContents = reportService.readReport(reportPath, 50000);

        assertTrue(reportContents.contains("Report artifact"));
        assertTrue(reportContents.contains("Codex Test Alert"));
    }

    private static void awaitApiReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                clientApi.core.version();
                return;
            } catch (ClientApiException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("ZAP API did not become ready within 30 seconds");
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
