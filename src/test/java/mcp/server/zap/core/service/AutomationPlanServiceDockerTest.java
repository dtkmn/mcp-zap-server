package mcp.server.zap.core.service;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import mcp.server.zap.core.gateway.ZapEngineAutomationAccess;
import mcp.server.zap.core.gateway.ZapEngineRuntimeAccess;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("docker")
@Testcontainers
class AutomationPlanServiceDockerTest {
    private static final Pattern PLAN_ID_PATTERN = Pattern.compile("Plan ID: ([^\\n]+)");
    private static final Pattern PLAN_FILE_PATTERN = Pattern.compile("Plan File: ([^\\n]+)");
    private static final Duration AUTOMATION_API_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration AUTOMATION_PLAN_COMPLETION_TIMEOUT = Duration.ofSeconds(30);
    private static final Network NETWORK = Network.newNetwork();
    private static final Path AUTOMATION_ROOT = createAutomationRoot();
    private static final Path AUTOMATION_READINESS_PLAN = createAutomationReadinessPlan();

    @Container
    static final GenericContainer<?> TARGET =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("automation-target")
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
                            "api.addrs.addr.regex=true",
                            "-addoninstall",
                            "automation"
                    )
                    .withCreateContainerCmdModifier(cmd -> {
                        cmd.withUser("0:0");
                        addHostBind(cmd, AUTOMATION_ROOT);
                    })
                    .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static AutomationPlanService service;
    private static ZapEngineAutomationAccess automationAccess;
    private static ClientApi clientApi;
    private static String targetUrl;

    @BeforeAll
    static void setupService() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);
        awaitAutomationApiReady(clientApi);

        String targetIp = TARGET.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
        targetUrl = "http://" + targetIp + "/";
        UrlValidationService policy = new UrlValidationService();
        ReflectionTestUtils.setField(policy, "allowPrivateNetworks", true);
        ReflectionTestUtils.setField(policy, "whitelist", List.of(targetIp));
        ReflectionTestUtils.setField(policy, "blacklist", List.of());
        automationAccess = spy(new ZapEngineAutomationAccess(clientApi));
        service = new AutomationPlanService(automationAccess, policy);
        ZapEngineRuntimeAccess runtimeAccess = new ZapEngineRuntimeAccess(clientApi);
        service.setRuntimeAccess(runtimeAccess);
        var inventory = runtimeAccess.readScannerInventory();
        String automationVersion = inventory.addOns().stream().filter(addOn -> "automation".equals(addOn.id()))
                .map(addOn -> addOn.version()).findFirst().orElse("unknown");
        System.out.printf("Automation fixture: ZAP %s, automation %s%n", inventory.zapVersion(), automationVersion);
        ReflectionTestUtils.setField(service, "automationLocalDirectory", AUTOMATION_ROOT.toString());
        ReflectionTestUtils.setField(service, "automationZapDirectory", AUTOMATION_ROOT.toString());
    }

    @Test
    void automationPlanRunStatusAndArtifactsWorkAgainstRealZap() throws Exception {
        String runResponse = service.runAutomationPlan(
                null,
                """
                        env:
                          contexts:
                            - name: local-target
                              urls:
                                - %s
                          parameters:
                            progressToStdout: true
                            maxDuration: 300
                        jobs:
                          - type: requestor
                            requests:
                              - url: %s
                                method: GET
                                responseCode: 200
                          - type: passiveScan-wait
                            parameters:
                              maxDuration: 1
                          - type: spider
                            parameters:
                              context: local-target
                              maxDepth: 1
                              maxDuration: 1
                          - type: report
                            parameters:
                              template: traditional-json-plus
                              reportFile: automation-report
                              reportTitle: Automation Test Report
                              displayReport: false
                            sites:
                              - %s
                        """.formatted(targetUrl, targetUrl, targetUrl),
                "smoke-plan.yaml"
        );

        String planId = extractValue(PLAN_ID_PATTERN, runResponse);
        String planPath = extractValue(PLAN_FILE_PATTERN, runResponse);
        String finalStatus = awaitCompletedStatus(planId);
        String artifacts = service.getAutomationPlanArtifacts(planPath, 10, 12000);

        assertTrue(runResponse.contains("Automation plan started."), runResponse);
        assertTrue(finalStatus.contains("Completed: yes"), finalStatus);
        assertTrue(finalStatus.contains("Successful: unknown"), finalStatus);
        assertTrue(finalStatus.contains("Job report generated report"), finalStatus);
        assertTrue(artifacts.contains("automation-report.json"), artifacts);
        assertTrue(artifacts.contains("\"@programName\": \"ZAP\""), artifacts);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wholePlanDeadlineStopsFollowingRequestsAndHonorsAlwaysRunReports(boolean alwaysRun) throws Exception {
        String markerUrl = targetUrl + "deadline-marker-" + UUID.randomUUID();
        String runResponse = service.runAutomationPlan(null,
                delayedPlan(2, alwaysRun, markerUrl, targetUrl), "deadline-plan.yaml");
        String planId = extractValue(PLAN_ID_PATTERN, runResponse);
        String planPath = extractValue(PLAN_FILE_PATTERN, runResponse);

        String finalStatus = awaitCompletedStatus(planId);
        var progress = automationAccess.loadAutomationPlanProgress(planId);
        String artifacts = service.getAutomationPlanArtifacts(planPath, 10, 12000);

        assertFalse(progress.warnings().isEmpty(), finalStatus);
        assertTrue(progress.errors().isEmpty(), finalStatus);
        assertTrue(finalStatus.contains("Successful: unknown"), finalStatus);
        assertFalse(engineRecordedUrl(markerUrl), "The ordinary request after the interrupted delay must not execute");
        if (alwaysRun) {
            assertTrue(artifacts.contains("deadline-report.json"), artifacts);
            assertTrue(artifacts.contains("\"@programName\": \"ZAP\""), artifacts);
        } else {
            assertTrue(artifacts.contains("no generated files found yet"), artifacts);
        }
        System.out.printf("Whole-plan expiry: alwaysRun=%s, status=%s%n", alwaysRun, finalStatus);
    }

    @Test
    void nativeCancellationDoesNotBecomeSuccessWhenTheEngineReportsNoErrors() throws Exception {
        String markerUrl = targetUrl + "cancel-marker-" + UUID.randomUUID();
        String runResponse = service.runAutomationPlan(null,
                delayedPlan(300, true, markerUrl, targetUrl), "cancel-plan.yaml");
        String planId = extractValue(PLAN_ID_PATTERN, runResponse);
        String planPath = extractValue(PLAN_FILE_PATTERN, runResponse);
        awaitDelayStarted(planId);
        clientApi.automation.stopPlan(planId);

        String finalStatus = awaitCompletedStatus(planId);
        var progress = automationAccess.loadAutomationPlanProgress(planId);
        assertTrue(progress.errors().isEmpty(), finalStatus);
        assertTrue(finalStatus.contains("Successful: unknown"), finalStatus);
        assertFalse(engineRecordedUrl(markerUrl), "Cancellation must skip the following ordinary request");
        String artifacts = service.getAutomationPlanArtifacts(planPath, 10, 12000);
        assertTrue(artifacts.contains("no generated files found yet"), artifacts);
        System.out.printf("Native cancellation status: %s%n", finalStatus);
    }

    @Test
    void requestFailureWarningStopsOrdinaryWorkWithoutClaimingSuccess() throws Exception {
        String markerUrl = targetUrl + "failure-marker-" + UUID.randomUUID();
        String refusedUrl = "http://" + URI.create(targetUrl).getHost() + ":1/";
        String runResponse = service.runAutomationPlan(null,
                delayedPlan(300, true, markerUrl, refusedUrl).replace("failOnWarning: false", "failOnWarning: true"),
                "failure-plan.yaml");
        String planId = extractValue(PLAN_ID_PATTERN, runResponse);
        String finalStatus = awaitCompletedStatus(planId);
        var progress = automationAccess.loadAutomationPlanProgress(planId);
        assertFalse(progress.warnings().isEmpty(), finalStatus);
        assertTrue(progress.errors().isEmpty(), finalStatus);
        assertTrue(finalStatus.contains("Successful: unknown"), finalStatus);
        assertFalse(engineRecordedUrl(markerUrl), "The requestor failure must skip subsequent ordinary jobs");
        String artifacts = service.getAutomationPlanArtifacts(extractValue(PLAN_FILE_PATTERN, runResponse), 10, 12000);
        assertTrue(artifacts.contains("deadline-report.json"), artifacts);
        System.out.printf("Request failure warning status: %s%n", finalStatus);
    }

    private static String delayedPlan(int maxDurationSeconds, boolean alwaysRun, String markerUrl, String requestUrl) {
        return """
                env:
                  contexts:
                    - name: deadline-target
                      urls: [%s]
                  parameters:
                    maxDuration: %d
                    failOnError: true
                    failOnWarning: false
                    continueOnFailure: false
                jobs:
                  - type: requestor
                    requests:
                      - url: %s
                        method: GET
                        responseCode: 200
                  - type: delay
                    name: deadline-delay
                    parameters:
                      time: "60"
                  - type: requestor
                    name: ordinary-after-delay
                    requests:
                      - url: %s
                        method: GET
                  - type: report
                    alwaysRun: %s
                    parameters:
                      template: traditional-json-plus
                      reportFile: deadline-report
                      displayReport: false
                    sites: [%s]
                """.formatted(targetUrl, maxDurationSeconds, requestUrl, markerUrl, alwaysRun, targetUrl);
    }

    private static boolean engineRecordedUrl(String url) throws ClientApiException {
        ApiResponse response = clientApi.core.urls(targetUrl);
        if (!(response instanceof ApiResponseList urls)) {
            throw new IllegalStateException("Unexpected URL inventory response");
        }
        return urls.getItems().stream().anyMatch(item -> item instanceof ApiResponseElement element
                && url.equals(element.getValue()));
    }

    private static void awaitDelayStarted(String planId) throws Exception {
        long deadline = System.nanoTime() + AUTOMATION_PLAN_COMPLETION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var progress = automationAccess.loadAutomationPlanProgress(planId);
            if (progress.info().stream().anyMatch(message -> message.contains("Job delay started"))) {
                return;
            }
            if (progress.completed()) {
                throw new IllegalStateException("Plan finished before the cancellation fixture reached its delay");
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Cancellation fixture did not reach its delay job");
    }

    @Test
    void destinationPolicyRejectsInlineAndFilePlansBeforeRealZapDispatch() throws Exception {
        org.mockito.Mockito.clearInvocations(automationAccess);
        String forbiddenPlan = "env: {contexts: [{name: forbidden, urls: ['http://127.0.0.1/']}] }\njobs: []\n";
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null, forbiddenPlan, "forbidden.yaml"));
        Path file = AUTOMATION_ROOT.resolve("forbidden-file.yaml");
        Files.writeString(file, "env: {contexts: [{name: allowed, urls: ['" + targetUrl + "']}] }\n"
                + "jobs: [{type: requestor, requests: [{url: 'http://169.254.169.254/'}]}]\n");
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(file.toString(), null, null));
        verify(automationAccess, never()).runAutomationPlan(anyString());
    }

    private static void awaitAutomationApiReady(ClientApi clientApi) throws Exception {
        long deadline = System.nanoTime() + AUTOMATION_API_READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                ApiResponse response = clientApi.automation.runPlan(AUTOMATION_READINESS_PLAN.toString());
                String planId = requireResponseElementValue(response, "automation.runPlan()");
                awaitProbePlanCompletion(clientApi, planId);
                return;
            } catch (ClientApiException e) {
                if ("Does Not Exist".equals(e.getMessage())) {
                    Thread.sleep(500);
                    continue;
                }
                throw new IllegalStateException("Automation API probe failed unexpectedly: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException(
                "Automation API did not become ready within "
                        + AUTOMATION_API_READY_TIMEOUT.toSeconds()
                        + " seconds");
    }

    private static void awaitProbePlanCompletion(ClientApi clientApi, String planId) throws Exception {
        long deadline = System.nanoTime() + AUTOMATION_PLAN_COMPLETION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            ApiResponse response = clientApi.automation.planProgress(planId);
            if (response instanceof ApiResponseSet responseSet) {
                String finished = responseSet.getStringValue("finished");
                if (finished != null && !finished.isBlank()) {
                    return;
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "Automation readiness probe plan did not complete within "
                        + AUTOMATION_PLAN_COMPLETION_TIMEOUT.toSeconds()
                        + " seconds");
    }

    private static String awaitCompletedStatus(String planId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String latest = "";
        while (System.nanoTime() < deadline) {
            latest = service.getAutomationPlanStatus(planId, 30);
            if (latest.contains("Completed: yes")) {
                return latest;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Automation plan did not complete in time. Latest status:\n" + latest);
    }

    private static String extractValue(Pattern pattern, String response) {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            throw new IllegalStateException("Pattern not found in response: " + response);
        }
        return matcher.group(1).trim();
    }

    private static String requireResponseElementValue(ApiResponse response, String operationName) {
        if (response instanceof ApiResponseElement element) {
            return element.getValue();
        }
        throw new IllegalStateException("Unexpected response from " + operationName + ": " + response);
    }

    private static Path createAutomationRoot() {
        try {
            return makeZapAccessible(Files.createTempDirectory("zap-automation-smoke"));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create automation workspace", e);
        }
    }

    private static Path createAutomationReadinessPlan() {
        Path readinessPlan = AUTOMATION_ROOT.resolve("automation-readiness-probe.yaml");
        try {
            Files.writeString(readinessPlan, """
                    env:
                      contexts:
                        - name: readiness-target
                          urls:
                            - http://automation-target/
                    jobs:
                      - type: requestor
                        requests:
                          - url: http://automation-target/
                            method: GET
                            responseCode: 200
                    """);
            return readinessPlan;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create automation readiness probe plan", e);
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
        } catch (UnsupportedOperationException _) {
            boolean readable = directory.toFile().setReadable(true, false);
            boolean writable = directory.toFile().setWritable(true, false);
            boolean executable = directory.toFile().setExecutable(true, false);
            if (!readable || !writable || !executable) {
                fail("Unable to make automation workspace accessible to ZAP");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to make automation workspace accessible to ZAP", e);
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
