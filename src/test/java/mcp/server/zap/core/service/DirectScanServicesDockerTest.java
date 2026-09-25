package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.gateway.ZapEngineContextAccess;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.auth.bootstrap.AuthProfileResolver;
import mcp.server.zap.core.service.auth.bootstrap.CredentialReferenceResolver;
import mcp.server.zap.core.service.auth.bootstrap.FormLoginAuthBootstrapProvider;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.auth.bootstrap.InMemoryPreparedAuthSessionRegistry;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                    .withCopyToContainer(Transferable.of("""
                            <!doctype html>
                            <html><body><script>
                            const next = new URL(location.href);
                            const step = Number(next.searchParams.get('step') || 0) + 1;
                            next.searchParams.set('step', step);
                            const link = document.createElement('a');
                            link.href = next.href;
                            link.textContent = 'Continue to page ' + step;
                            document.body.appendChild(link);
                            </script></body></html>
                            """), "/usr/share/nginx/html/client-duration.html")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> AUTH_TARGET =
            new GenericContainer<>(DockerImageName.parse("python:3.13-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("client-auth-target")
                    .withCopyToContainer(MountableFile.forClasspathResource("client-spider/form-login.py"),
                            "/form-login.py")
                    .withCommand("python", "/form-login.py")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(ZapDockerTestSupport.zapImage())
                    .withNetwork(NETWORK)
                    .dependsOn(TARGET, AUTH_TARGET)
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

    @ParameterizedTest
    @EnumSource(GuidedExecutionModeResolver.ExecutionMode.class)
    void guidedClientSpiderUsesPreparedBrowserSessionForProtectedJavaScriptTraffic(
            GuidedExecutionModeResolver.ExecutionMode mode, @TempDir Path temporaryDirectory)
            throws Exception {
        assertPreparedBrowserSessionCrawl(mode, temporaryDirectory, false);
    }

    @ParameterizedTest
    @EnumSource(GuidedExecutionModeResolver.ExecutionMode.class)
    void guidedClientSpiderValidatesAndCrawlsWithBearerTokenStoredInLocalStorage(
            GuidedExecutionModeResolver.ExecutionMode mode, @TempDir Path temporaryDirectory)
            throws Exception {
        assertPreparedBrowserSessionCrawl(mode, temporaryDirectory, true);
    }

    private void assertPreparedBrowserSessionCrawl(GuidedExecutionModeResolver.ExecutionMode mode,
                                                   Path temporaryDirectory, boolean bearerSession) throws Exception {
        String targetOrigin = "http://client-auth-target:8080";
        String authFlavor = bearerSession ? "bearer" : "cookie";
        String crawlQuery = "?crawl=" + mode.name();
        String crawlPath = bearerSession ? "/bearer/app" : "/protected";
        String verificationPath = bearerSession ? "/bearer/user" : "/protected";
        String discoveredPath = bearerSession ? "/bearer/client-discovered" : "/protected/client-discovered";
        String loginPath = bearerSession ? "/bearer/login" : "/login";
        String expectedAuthHeader = bearerSession ? "Authorization: Bearer " : "Cookie: session=";
        String targetUrl = targetOrigin + crawlPath + crawlQuery;
        String verificationUrl = bearerSession ? targetOrigin + verificationPath : targetUrl;
        String localOrigin = "http://" + AUTH_TARGET.getHost() + ":" + AUTH_TARGET.getMappedPort(8080);
        try (HttpClient http = HttpClient.newHttpClient()) {
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(localOrigin + verificationPath)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "The verification endpoint must require a login");
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(localOrigin + discoveredPath))
                            .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "The JavaScript resource must also require a login");
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(localOrigin + loginPath))
                            .POST(HttpRequest.BodyPublishers.ofString(bearerSession
                                    ? "{\"username\":\"scan-user\",\"password\":\"wrong\"}"
                                    : "username=scan-user&password=wrong"))
                            .header("Content-Type", bearerSession ? "application/json" : "application/x-www-form-urlencoded").build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode(),
                    "The fixture must reject incorrect credentials");
        }

        Path passwordFile = Files.writeString(temporaryDirectory.resolve("password"), "fixture-password");
        AuthBootstrapProperties.Profile profile = new AuthBootstrapProperties.Profile();
        profile.setId("client-browser-auth-" + authFlavor + "-" + mode.name());
        profile.setKind("browser");
        profile.setAllowedOrigin(targetOrigin);
        profile.setCredentialReference("file:" + passwordFile);
        profile.setLoginUrl(targetOrigin + loginPath);
        profile.setUsername("scan-user");
        profile.setZapUserName("client-browser-user-" + authFlavor + "-" + mode.name());
        profile.setLoggedInIndicatorRegex("Signed in as scan-user");
        profile.setLoggedOutIndicatorRegex("Login required");
        AuthBootstrapProperties authProperties = new AuthBootstrapProperties();
        authProperties.setProfiles(List.of(profile));
        // Browser authentication is synchronous; deployments can select this existing API read timeout.
        ClientApi browserAuthApi = new TimeoutZapClientApi(ZAP.getHost(), ZAP.getMappedPort(8090), null, 5000, 60000);
        FormLoginAuthBootstrapProvider authProvider = new FormLoginAuthBootstrapProvider(
                new ContextUserService(new ZapEngineContextAccess(browserAuthApi)),
                new CredentialReferenceResolver(), mock(UrlValidationService.class));
        GuidedAuthSessionService authSessions = new GuidedAuthSessionService(
                List.of(authProvider), new InMemoryPreparedAuthSessionRegistry(), new AuthProfileResolver(authProperties));
        String prepared = authSessions.prepareSession(profile.getId(), verificationUrl);
        String sessionId = prepared.lines().filter(line -> line.startsWith("Session ID: "))
                .map(line -> line.substring("Session ID: ".length())).findFirst().orElseThrow();
        String validated = authSessions.validateSession(sessionId);
        assertTrue(validated.contains("Valid: true"), validated);
        assertTrue(validated.contains("Outcome: authenticated"), validated);
        if (bearerSession) {
            var session = authSessions.getPreparedSession(sessionId);
            ApiResponseSet nativePoll = (ApiResponseSet) browserAuthApi.users.pollAsUser(session.contextId(), session.userId());
            assertEquals("true", nativePoll.getStringValue("pollSuccessful"),
                    "ZAP must replay the browser's bearer token when verifying authentication outside the browser");
            assertTrue(nativePoll.getStringValue("responseHeader").contains(" 200 "));
            assertTrue(nativePoll.getStringValue("responseBody").contains("Signed in as scan-user"));
        }

        GuidedExecutionModeResolver executionMode = mock(GuidedExecutionModeResolver.class);
        when(executionMode.resolveDefaultMode()).thenReturn(mode);
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        var retryPolicy = new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0);
        ScanJobQueueService queue = new ScanJobQueueService(
                activeScanService, spiderScanService, mock(AjaxSpiderService.class), clientSpiderService,
                mock(UrlValidationService.class), new ScanLimitProperties(), retryPolicy, retryPolicy,
                false, store, new SingleNodeQueueLeadershipCoordinator());
        GuidedScanWorkflowService guidedScans = new GuidedScanWorkflowService(executionMode,
                mock(SpiderScanService.class), mock(AjaxSpiderService.class), clientSpiderService,
                mock(ActiveScanService.class), queue, authSessions,
                new ZapEngineAdapter(), new GatewayRecordFactory());
        try {
            String started = guidedScans.startCrawl(targetUrl, "client", null, sessionId);
            String operationId = started.lines().filter(line -> line.startsWith("Operation ID: "))
                    .map(line -> line.substring("Operation ID: ".length())).findFirst().orElseThrow();
            String jobId = mode == GuidedExecutionModeResolver.ExecutionMode.QUEUE
                    ? store.list().getFirst().getId() : null;
            try {
                assertTrue(started.contains("Authenticated Session: " + sessionId));
                String status = guidedScans.getCrawlStatus(operationId);
                assertTrue(status.contains("Strategy: client"));
                if (jobId != null) {
                    var job = store.load(jobId).orElseThrow();
                    assertEquals(ScanJobType.CLIENT_SPIDER, job.getType());
                    assertEquals(ScanJobStatus.RUNNING, job.getStatus());
                    assertEquals(profile.getId() + "-auth", job.getParameters().get("contextName"));
                    assertEquals(profile.getZapUserName(), job.getParameters().get("userName"));
                    assertTrue(status.contains("Job ID: " + jobId));
                    assertTrue(status.contains("ZAP Scan ID: " + job.getZapScanId()));
                }
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                    ApiResponseList messages = (ApiResponseList) clientApi.core.messages(
                            targetOrigin + discoveredPath + crawlQuery, "0", "100");
                    assertTrue(messages.getItems().stream().map(ApiResponseSet.class::cast).anyMatch(message ->
                                    message.getStringValue("requestHeader").contains(discoveredPath + crawlQuery)
                                            && message.getStringValue("requestHeader").contains(expectedAuthHeader)
                                            && message.getStringValue("responseHeader").contains(" 200 ")
                                            && message.getStringValue("responseBody")
                                                    .contains("authenticated JavaScript resource")),
                            "Client Spider must fetch this crawl's protected JavaScript resource with its browser user's session");
                });
                String scanId = jobId == null ? extractScanId(started) : store.load(jobId).orElseThrow().getZapScanId();
                assertTrue(clientSpiderService.getClientSpiderProgressPercent(scanId) < 100,
                        "The crawl must still be running so the stop request exercises active cancellation");
            } finally {
                guidedScans.stopCrawl(operationId);
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                    String status = guidedScans.getCrawlStatus(operationId);
                    String scanId = jobId == null ? extractScanId(started) : store.load(jobId).orElseThrow().getZapScanId();
                    assertEquals(100, clientSpiderService.getClientSpiderProgressPercent(scanId));
                    if (jobId != null) {
                        assertEquals(ScanJobStatus.CANCELLED, store.load(jobId).orElseThrow().getStatus());
                        assertTrue(status.contains("Status: CANCELLED"));
                    }
                });
            }
        } finally {
            queue.shutdownExecutor();
        }

        if (bearerSession) {
            return;
        }

        String unexpectedPrepared = authSessions.prepareSession(profile.getId(), targetOrigin + "/unexpected-response");
        String unexpectedSessionId = unexpectedPrepared.lines().filter(line -> line.startsWith("Session ID: "))
                .map(line -> line.substring("Session ID: ".length())).findFirst().orElseThrow();
        String unexpectedValidation = authSessions.validateSession(unexpectedSessionId);
        assertTrue(unexpectedValidation.contains("Valid: false"), unexpectedValidation);
        assertTrue(unexpectedValidation.contains("Outcome: authentication_unconfirmed"), unexpectedValidation);

        var unexpectedSession = authSessions.getPreparedSession(unexpectedSessionId);
        ApiResponseSet unexpectedPoll = (ApiResponseSet) browserAuthApi.users.pollAsUser(
                unexpectedSession.contextId(), unexpectedSession.userId());
        assertEquals("true", unexpectedPoll.getStringValue("pollSuccessful"),
                "ZAP's native verdict alone accepts a response without either configured indicator");
        assertTrue(unexpectedPoll.getStringValue("responseHeader").contains(" 500 "));
        assertTrue(unexpectedPoll.getStringValue("responseBody").contains("Temporarily unavailable"));
    }

    @Test
    void clientSpiderDurationExpiresWithoutChangingAnotherScansLimit() throws Exception {
        String previousPageLoadTime = ((ApiResponseElement) clientApi.callApi(
                "clientSpider", "view", "optionPageLoadTimeInSecs", Map.of())).getValue();
        String durationStatistic = "stats.client.spider.event.max.time";
        String limitedScanId = null;
        String unlimitedScanId = null;
        try {
            clientApi.callApi("clientSpider", "action", "setOptionPageLoadTimeInSecs", Map.of("Integer", "2"));
            clientApi.stats.clearStats(durationStatistic);

            limitedScanId = extractScanId(clientSpiderService.startClientSpider(
                    "http://direct-scan-target/client-duration.html?crawl=limited", 0));

            ScanLimitProperties unlimitedProperties = new ScanLimitProperties();
            unlimitedProperties.setMaxSpiderScanDurationInMins(0);
            ClientSpiderService unlimitedService = new ClientSpiderService(
                    new ZapEngineScanExecution(clientApi), mock(UrlValidationService.class), unlimitedProperties);
            unlimitedScanId = unlimitedService.startClientSpiderJob(
                    "http://direct-scan-target/client-duration.html?crawl=unlimited", 0);

            String scanToExpire = limitedScanId;
            await().atMost(Duration.ofSeconds(100)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> clientSpiderService.getClientSpiderProgressPercent(scanToExpire) == 100);

            ApiResponseSet statistics = (ApiResponseSet) clientApi.stats.stats(durationStatistic);
            String expirations = statistics.getStringValue(durationStatistic);
            assertTrue(expirations != null && Long.parseLong(expirations) > 0,
                    "The limited crawl must finish because ZAP enforced its duration, not because the fixture ended");
            assertTrue(unlimitedService.getClientSpiderProgressPercent(unlimitedScanId) < 100,
                    "A subsequent unlimited crawl must stay running without removing the first scan's saved limit");
        } finally {
            if (limitedScanId != null) {
                clientSpiderService.stopClientSpiderJob(limitedScanId);
            }
            if (unlimitedScanId != null) {
                clientSpiderService.stopClientSpiderJob(unlimitedScanId);
            }
            clientApi.callApi("clientSpider", "action", "setOptionPageLoadTimeInSecs",
                    Map.of("Integer", previousPageLoadTime));
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
