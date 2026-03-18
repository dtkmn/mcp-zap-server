package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
public class ActiveScanPolicyToolsDockerTest {

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(DockerImageName.parse("zaproxy/zap-stable:2.17.0"))
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

    private static ActiveScanService service;

    @BeforeAll
    static void setupService() throws Exception {
        ClientApi clientApi = new ClientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        awaitApiReady(clientApi);
        service = new ActiveScanService(clientApi, mock(UrlValidationService.class), new ScanLimitProperties());
    }

    @Test
    void scanPolicyToolsWorkAgainstRealZap() throws Exception {
        String list = service.listScanPolicies();
        String originalView = service.viewScanPolicy("Default Policy", "40012", "1");

        assertTrue(list.contains("Default Policy"));
        assertTrue(originalView.contains("Policy: Default Policy"));
        assertTrue(originalView.contains("40012 | Cross Site Scripting (Reflected)"));

        try {
            String update = service.setScanPolicyRuleState("Default Policy", "40012", "false", null, "OFF");
            String updatedView = service.viewScanPolicy("Default Policy", "40012", "1");

            assertTrue(update.contains("Active-scan policy updated."));
            assertTrue(update.contains("40012 | Cross Site Scripting (Reflected)"));
            assertTrue(updatedView.contains("enabled=no"));
            assertTrue(updatedView.contains("threshold=OFF"));
        } finally {
            service.setScanPolicyRuleState("Default Policy", "40012", "true", null, "DEFAULT");
        }

        String restoredView = service.viewScanPolicy("Default Policy", "40012", "1");
        assertTrue(restoredView.contains("enabled=yes"));
        assertTrue(restoredView.contains("threshold=DEFAULT"));
    }

    private static void awaitApiReady(ClientApi clientApi) throws Exception {
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
}
