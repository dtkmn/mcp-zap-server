package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;

import java.time.Duration;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("docker")
@Testcontainers
class ActiveScanPolicyToolsDockerTest {
    private static final String DEFAULT_POLICY = "Preservation Default";
    private static final String CUSTOM_POLICY = "Preservation Custom";
    private static final String TARGET_URL = "http://policy-preservation-target/";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("policy-preservation-target")
                    .withCopyToContainer(Transferable.of("<html><body>Policy fixture</body></html>"),
                            "/usr/share/nginx/html/index.html")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(ZapDockerTestSupport.zapImage())
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
                    .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static ClientApi clientApi;
    private static ZapEngineScanExecution execution;
    private static ActiveScanService service;
    private static String contextId;
    private static String userId;

    @BeforeAll
    static void setupService() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);
        execution = new ZapEngineScanExecution(clientApi);
        ScanLimitProperties limits = new ScanLimitProperties();
        limits.setMaxActiveScanDurationInMins(1);
        limits.setHostPerScan(1);
        limits.setThreadPerHost(1);
        service = new ActiveScanService(
                execution,
                mock(UrlValidationService.class),
                limits
        );
        clientApi.ascan.setOptionDefaultPolicy("Default Policy");
        clientApi.ascan.addScanPolicy(DEFAULT_POLICY, "MEDIUM", "LOW");
        clientApi.ascan.addScanPolicy(CUSTOM_POLICY, "MEDIUM", "HIGH");
        clientApi.core.accessUrl(TARGET_URL, "false");
        contextId = ((ApiResponseElement) clientApi.context.newContext("Policy preservation")).getValue();
        clientApi.context.includeInContext("Policy preservation", "http://policy-preservation-target/.*");
        // Exercise the scanAsUser route on a public fixture; this does not test a login flow.
        userId = ((ApiResponseElement) clientApi.users.newUser(contextId, "policy-user")).getValue();
        clientApi.users.setUserEnabled(contextId, userId, "true");
    }

    @Test
    void scanPolicyToolsWorkAgainstRealZap() {
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

    @ParameterizedTest(name = "asUser={0}, policy={1}")
    @MethodSource("scanPolicySelections")
    void startingScanPreservesDefaultAndCustomPolicies(boolean asUser, String policy) throws Exception {
        String originalDefault = ((ApiResponseElement) clientApi.ascan.optionDefaultPolicy()).getValue();
        try {
            clientApi.ascan.setOptionDefaultPolicy(DEFAULT_POLICY);
            // No attack rules are needed to verify a real START preserves stored policy state.
            clientApi.ascan.disableAllScanners(DEFAULT_POLICY);
            clientApi.ascan.disableAllScanners(CUSTOM_POLICY);
            var defaultBefore = execution.loadActiveScanPolicyRules(DEFAULT_POLICY);
            var customBefore = execution.loadActiveScanPolicyRules(CUSTOM_POLICY);
            assertFalse(defaultBefore.isEmpty());
            assertFalse(customBefore.isEmpty());
            assertTrue(defaultBefore.stream().noneMatch(rule -> rule.enabled()));
            assertTrue(customBefore.stream().noneMatch(rule -> rule.enabled()));

            String scanId = asUser
                    ? service.startActiveScanAsUserJob(contextId, userId, TARGET_URL, "false", policy, NativeScanLaunch.UNTRACKED)
                    : service.startActiveScanJob(TARGET_URL, "false", policy, NativeScanLaunch.UNTRACKED);

            assertFalse(scanId.isBlank());
            assertAll(
                    () -> assertEquals(defaultBefore, execution.loadActiveScanPolicyRules(DEFAULT_POLICY),
                            "Starting any scan must preserve every rule in the effective default policy"),
                    () -> assertEquals(customBefore, execution.loadActiveScanPolicyRules(CUSTOM_POLICY),
                            "Starting a scan must preserve every rule in the custom policy"),
                    () -> assertEquals(DEFAULT_POLICY,
                            ((ApiResponseElement) clientApi.ascan.optionDefaultPolicy()).getValue(),
                            "Selecting a policy must not replace the effective default")
            );
            await().atMost(Duration.ofSeconds(30)).until(() -> service.getActiveScanProgressPercent(scanId) == 100);
        } finally {
            clientApi.ascan.stopAllScans();
            clientApi.ascan.setOptionDefaultPolicy(originalDefault);
        }
    }

    private static Stream<Arguments> scanPolicySelections() {
        return Stream.of(false, true).flatMap(asUser ->
                Stream.of(null, DEFAULT_POLICY, CUSTOM_POLICY).map(policy -> Arguments.of(asUser, policy)));
    }

}
