package mcp.server.zap.core.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import mcp.server.zap.core.gateway.ZapEngineAutomationAccess;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
public class AutomationPlanServiceDockerTest {
    private static final Pattern PLAN_ID_PATTERN = Pattern.compile("Plan ID: ([^\\n]+)");
    private static final Pattern PLAN_FILE_PATTERN = Pattern.compile("Plan File: ([^\\n]+)");
    private static final Duration ZAP_API_READY_TIMEOUT = Duration.ofSeconds(90);
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
            new GenericContainer<>(DockerImageName.parse("zaproxy/zap-stable:2.17.0"))
                    .withNetwork(NETWORK)
                    .dependsOn(TARGET)
                    .withExposedPorts(8090)
                    .withFileSystemBind(AUTOMATION_ROOT.toString(), AUTOMATION_ROOT.toString())
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
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0:0"))
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static AutomationPlanService service;

    @BeforeAll
    static void setupService() throws Exception {
        ClientApi clientApi = new ClientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        awaitApiReady(clientApi);
        awaitAutomationApiReady(clientApi);

        service = new AutomationPlanService(new ZapEngineAutomationAccess(clientApi));
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
                                - http://automation-target/
                          parameters:
                            progressToStdout: true
                        jobs:
                          - type: requestor
                            requests:
                              - url: http://automation-target/
                                method: GET
                                responseCode: 200
                          - type: passiveScan-wait
                            parameters:
                              maxDuration: 1
                          - type: report
                            parameters:
                              template: traditional-json-plus
                              reportFile: automation-report
                              reportTitle: Automation Test Report
                              displayReport: false
                            sites:
                              - automation-target
                        """,
                "smoke-plan.yaml"
        );

        String planId = extractValue(PLAN_ID_PATTERN, runResponse);
        String planPath = extractValue(PLAN_FILE_PATTERN, runResponse);
        String finalStatus = awaitCompletedStatus(planId);
        String artifacts = service.getAutomationPlanArtifacts(planPath, 10, 12000);

        assertTrue(runResponse.contains("Automation plan started."), runResponse);
        assertTrue(finalStatus.contains("Completed: yes"), finalStatus);
        assertTrue(finalStatus.contains("Successful: yes"), finalStatus);
        assertTrue(finalStatus.contains("Job report generated report"), finalStatus);
        assertTrue(artifacts.contains("automation-report.json"), artifacts);
        assertTrue(artifacts.contains("\"@programName\": \"ZAP\""), artifacts);
    }

    private static void awaitApiReady(ClientApi clientApi) throws Exception {
        long deadline = System.nanoTime() + ZAP_API_READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                clientApi.core.version();
                return;
            } catch (ClientApiException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException(
                "ZAP API did not become ready within " + ZAP_API_READY_TIMEOUT.toSeconds() + " seconds");
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
        } catch (UnsupportedOperationException ignored) {
            directory.toFile().setReadable(true, false);
            directory.toFile().setWritable(true, false);
            directory.toFile().setExecutable(true, false);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to make automation workspace accessible to ZAP", e);
        }
        return directory;
    }
}
