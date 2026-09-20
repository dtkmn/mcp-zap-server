package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("docker")
@Testcontainers
class DirectScanServicesDockerTest {
    private static final Pattern SCAN_ID_PATTERN = Pattern.compile("Scan ID: ([^\\n]+)");
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("direct-scan-target")
                    .withCopyToContainer(Transferable.of("""
                            <!doctype html>
                            <html><body><h1>Client Spider fixture</h1>
                            <script>
                            fetch('/' + ['client', 'discovered'].join('-') + location.search);
                            </script></body></html>
                            """), "/usr/share/nginx/html/client-crawl.html")
                    .withCopyToContainer(Transferable.of("Discovered through JavaScript"),
                            "/usr/share/nginx/html/client-discovered")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(ZapDockerTestSupport.zapImage())
                    .withNetwork(NETWORK)
                    .dependsOn(TARGET)
                    .withExposedPorts(8090)
                    .withSharedMemorySize(512 * 1024 * 1024L)
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
                            "api.addrs.addr.regex=true",
                            "-addoninstall",
                            "client"
                    )
                    .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static ClientApi clientApi;
    private static ActiveScanService activeScanService;
    private static SpiderScanService spiderScanService;
    private static ClientSpiderService clientSpiderService;

    @BeforeAll
    static void setupServices() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);

        ScanLimitProperties scanLimitProperties = new ScanLimitProperties();
        scanLimitProperties.setMaxActiveScanDurationInMins(1);
        scanLimitProperties.setHostPerScan(2);
        scanLimitProperties.setThreadPerHost(2);
        scanLimitProperties.setSpiderThreadCount(2);
        scanLimitProperties.setMaxSpiderScanDurationInMins(1);
        scanLimitProperties.setSpiderMaxDepth(5);

        UrlValidationService urlValidationService = mock(UrlValidationService.class);
        ZapEngineScanExecution engineScanExecution = new ZapEngineScanExecution(clientApi);
        activeScanService = new ActiveScanService(engineScanExecution, urlValidationService, scanLimitProperties);
        spiderScanService = new SpiderScanService(engineScanExecution, urlValidationService, scanLimitProperties);
        clientSpiderService = new ClientSpiderService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void clientSpiderDiscoversJavaScriptTrafficAndStopsOnlyTheRequestedScan() throws Exception {
        String stoppedScanId = extractScanId(clientSpiderService.startClientSpider(
                "http://direct-scan-target/client-crawl.html?crawl=stop", 1));
        String continuingScanId = extractScanId(clientSpiderService.startClientSpider(
                "http://direct-scan-target/client-crawl.html?crawl=continue", 1));

        try {
            assertNotEquals(stoppedScanId, continuingScanId);
            assertTrue(clientSpiderService.getClientSpiderStatus(continuingScanId)
                    .contains("Scan ID: " + continuingScanId));

            clientSpiderService.stopClientSpider(stoppedScanId);
            assertTrue(clientSpiderService.getClientSpiderProgressPercent(continuingScanId) < 100,
                    "Stopping one Client Spider scan must leave the other scan running");

            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                    .until(() -> clientSpiderService.getClientSpiderProgressPercent(stoppedScanId) == 100);
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                    .until(() -> clientSpiderService.getClientSpiderProgressPercent(continuingScanId) == 100);

            ApiResponseList urlsResponse = (ApiResponseList) clientApi.core.urls("http://direct-scan-target/");
            List<String> discoveredUrls = urlsResponse.getItems().stream()
                    .map(ApiResponseElement.class::cast)
                    .map(ApiResponseElement::getValue)
                    .toList();
            assertTrue(discoveredUrls.contains("http://direct-scan-target/client-discovered?crawl=continue"),
                    () -> "The remaining crawl must execute JavaScript and send its discovered request through ZAP.\n"
                            + "Discovered URLs: " + discoveredUrls);
        } finally {
            clientSpiderService.stopClientSpiderJob(stoppedScanId);
            clientSpiderService.stopClientSpiderJob(continuingScanId);
        }
    }

    @Test
    void directSpiderAndActiveScanToolsWorkAgainstRealZap() throws Exception {
        String spiderStart = spiderScanService.startSpiderScan("http://direct-scan-target/");
        String spiderScanId = extractScanId(spiderStart);
        String spiderStatus = spiderScanService.getSpiderScanStatus(spiderScanId);

        assertTrue(spiderStart.contains("Direct spider scan started."));
        assertTrue(spiderStatus.contains("Scan ID: " + spiderScanId));

        clientApi.core.accessUrl("http://direct-scan-target/", "true");
        String activeStart = activeScanService.startActiveScan("http://direct-scan-target/", "true", null);
        String activeScanId = extractScanId(activeStart);
        String activeStatus = activeScanService.getActiveScanStatus(activeScanId);

        assertTrue(activeStart.contains("Direct active scan started."));
        assertTrue(activeStatus.contains("Scan ID: " + activeScanId));

        String activeStop = activeScanService.stopActiveScan(activeScanId);
        String spiderStop = spiderScanService.stopSpiderScan(spiderScanId);

        assertTrue(activeStop.contains("Direct active scan stopped."));
        assertTrue(spiderStop.contains("Direct spider scan stopped."));
    }

    private static String extractScanId(String response) {
        Matcher matcher = SCAN_ID_PATTERN.matcher(response);
        if (!matcher.find()) {
            throw new IllegalStateException("Scan ID not found in response: " + response);
        }
        return matcher.group(1).trim();
    }
}
