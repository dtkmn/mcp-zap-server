package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
public class DirectScanServicesDockerTest {
    private static final Pattern SCAN_ID_PATTERN = Pattern.compile("Scan ID: ([^\\n]+)");
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("direct-scan-target")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(DockerImageName.parse("zaproxy/zap-stable:2.17.0"))
                    .withNetwork(NETWORK)
                    .dependsOn(TARGET)
                    .withExposedPorts(8090)
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
    private static ActiveScanService activeScanService;
    private static SpiderScanService spiderScanService;

    @BeforeAll
    static void setupServices() throws Exception {
        clientApi = new ClientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        awaitApiReady();

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

    private static String extractScanId(String response) {
        Matcher matcher = SCAN_ID_PATTERN.matcher(response);
        if (!matcher.find()) {
            throw new IllegalStateException("Scan ID not found in response: " + response);
        }
        return matcher.group(1).trim();
    }
}
