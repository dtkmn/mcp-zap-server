package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.gateway.ZapEngineAjaxSpiderExecution;
import mcp.server.zap.core.gateway.ZapEngineContextAccess;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.auth.bootstrap.AuthProfileResolver;
import mcp.server.zap.core.service.auth.bootstrap.CredentialReferenceResolver;
import mcp.server.zap.core.service.auth.bootstrap.FormLoginAuthBootstrapProvider;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import mcp.server.zap.core.service.authz.GuidedToolActions;
import mcp.server.zap.core.service.auth.bootstrap.InMemoryPreparedAuthSessionRegistry;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                            server {
                                listen 80;
                                root /usr/share/nginx/html;
                                index index.html;
                                location = /http-slow { limit_rate 1024; }
                                location / { try_files $uri $uri/ =404; }
                            }
                            """), "/etc/nginx/conf.d/default.conf")
                    .withCopyToContainer(Transferable.of("""
                            <!doctype html>
                            <html><body><h1>Client Spider fixture</h1>
                            <script>
                            fetch('/' + ['client', 'discovered'].join('-') + location.search);
                            </script></body></html>
                            """), "/usr/share/nginx/html/client-crawl.html")
                    .withCopyToContainer(Transferable.of("""
                            <!doctype html>
                            <html><body><a href="/http-slow">Finite slow response</a>
                            <script>
                            fetch('/' + ['client', 'discovered'].join('-') + location.search);
                            </script></body></html>
                            """), "/usr/share/nginx/html/http-stop.html")
                    .withCopyToContainer(Transferable.of("x".repeat(64 * 1024)),
                            "/usr/share/nginx/html/http-slow")
                    .withCopyToContainer(Transferable.of("Discovered through JavaScript"),
                            "/usr/share/nginx/html/client-discovered")
                    .withCopyToContainer(Transferable.of("<!doctype html><html><body></body></html>"),
                            "/usr/share/nginx/html/client-idle.html")
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
                            "client",
                            "-addoninstall",
                            "spiderAjax"
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
        System.out.println("Crawler test ZAP core: " + clientApi.core.version().toString(0));
        System.out.println("Crawler test installed add-ons: " + clientApi.autoupdate.installedAddons().toString(0));
        String firefoxPath = ((ApiResponseElement) clientApi.selenium.optionFirefoxBinaryPath()).getValue();
        String driverPath = ((ApiResponseElement) clientApi.selenium.optionFirefoxDriverPath()).getValue();
        printBrowserVersion("Firefox", firefoxPath.isBlank() ? "firefox" : firefoxPath);
        printBrowserVersion("WebDriver", driverPath.isBlank() ? "geckodriver" : driverPath);

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

    @ParameterizedTest
    @MethodSource("guidedCrawlerModes")
    void guidedCrawlerSelectionKeepsNativeLifecycleAndMeasuredJavaScriptCoverage(
            String strategy, GuidedExecutionModeResolver.ExecutionMode mode) throws Exception {
        if ("browser".equals(strategy)) {
            // AJAX stop status does not prove that its global engine is idle. Give these
            // independent contract cases a fresh class-owned engine; this is test isolation,
            // not production recovery or a guarantee that restarting preserves scan state.
            ZAP.stop();
            ZAP.start();
            setupServices();
        }
        String effectiveStrategy = "auto".equals(strategy) ? "http" : strategy;
        String query = "?crawl=matrix-" + mode.name() + "-" + strategy;
        String origin = "http://direct-scan-target";
        boolean browserCrawl = !"http".equals(effectiveStrategy);
        String targetUrl = origin + (browserCrawl ? "/client-crawl.html" : "/http-stop.html") + query;
        String discoveredUrl = origin + "/client-discovered" + query;
        GuidedExecutionModeResolver executionMode = mock(GuidedExecutionModeResolver.class);
        when(executionMode.resolveDefaultMode()).thenReturn(mode);
        InMemoryScanJobStore store = new InMemoryScanJobStore();
        AjaxSpiderService ajaxSpider = new AjaxSpiderService(
                new ZapEngineAjaxSpiderExecution(clientApi), mock(UrlValidationService.class));
        ajaxSpider.setScanJobStore(store);
        var retryPolicy = new ScanJobQueueService.RetryPolicy(1, 0, 0, 1.0);
        ScanJobQueueService queue = new ScanJobQueueService(
                activeScanService, spiderScanService, ajaxSpider, clientSpiderService,
                mock(UrlValidationService.class), new ScanLimitProperties(), retryPolicy, retryPolicy,
                false, store, new SingleNodeQueueLeadershipCoordinator());
        GuidedScanWorkflowService guided = new GuidedScanWorkflowService(
                executionMode, spiderScanService, ajaxSpider, clientSpiderService, activeScanService,
                queue, mock(GuidedAuthSessionService.class), new ZapEngineAdapter(), new GatewayRecordFactory(),
                mock(GuidedToolActions.class));
        String operationId = null;
        try {
            String started = guided.startCrawl(targetUrl, strategy, null, null);
            operationId = extractResponseValue(started, "Operation ID: ");
            assertTrue(started.contains("Strategy: " + effectiveStrategy), started);
            assertTrue(started.contains("Execution Mode: " + mode.name().toLowerCase(Locale.ROOT)), started);
            String jobId = mode == GuidedExecutionModeResolver.ExecutionMode.QUEUE
                    ? extractResponseValue(started, "Job ID: ") : null;
            String scanId;
            if (jobId != null) {
                String operationToPoll = operationId;
                // Queue admission can precede backend startup. Poll through normal dispatch.
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    String queuedStatus = guided.getCrawlStatus(operationToPoll);
                    var pending = store.load(jobId).orElseThrow();
                    assertTrue(pending.getZapScanId() != null && !pending.getZapScanId().isBlank(), queuedStatus);
                });
                var job = store.load(jobId).orElseThrow();
                ScanJobType expectedType = switch (effectiveStrategy) {
                    case "client" -> ScanJobType.CLIENT_SPIDER;
                    case "browser" -> ScanJobType.AJAX_SPIDER;
                    default -> ScanJobType.SPIDER_SCAN;
                };
                assertEquals(expectedType, job.getType());
                scanId = job.getZapScanId();
            } else {
                scanId = "browser".equals(effectiveStrategy) ? null : extractScanId(started);
            }
            String status = guided.getCrawlStatus(operationId);
            assertTrue(status.contains("Strategy: " + effectiveStrategy), status);
            if (jobId != null) {
                assertTrue(status.contains("Job ID: " + jobId), status);
                assertTrue(status.contains("ZAP Scan ID: " + scanId), status);
            } else if (scanId != null) {
                assertTrue(status.contains("Scan ID: " + scanId), status);
            } else {
                assertTrue(status.contains("AJAX Spider Status:"), status);
            }
            if (browserCrawl) {
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                        .until(() -> recordedUrls(origin).contains(discoveredUrl));
                if ("client".equals(effectiveStrategy)) {
                    assertTrue(clientSpiderService.getClientSpiderProgressPercent(scanId) < 100,
                            "Client crawl must still be running when cancellation is requested");
                } else {
                    assertTrue(ajaxSpider.isAjaxSpiderRunning(),
                            "AJAX crawl must still be running when cancellation is requested");
                }
            } else {
                await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250))
                        .until(() -> {
                            int progress = spiderScanService.getSpiderScanProgressPercent(scanId);
                            return progress > 0 && progress < 100;
                        });
                assertFalse(recordedUrls(origin).contains(discoveredUrl),
                        "An HTTP or auto crawl must not be treated as JavaScript coverage");
                assertTrue(spiderScanService.getSpiderScanProgressPercent(scanId) < 100,
                        "HTTP crawl must still be running when cancellation is requested");
            }
            String operationToStop = operationId;
            assertTrue(guided.stopCrawl(operationToStop).contains("Guided crawl stop requested."));
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                String stopped = guided.getCrawlStatus(operationToStop);
                switch (effectiveStrategy) {
                    case "client" -> assertEquals(100, clientSpiderService.getClientSpiderProgressPercent(scanId));
                    case "browser" -> assertFalse(ajaxSpider.isAjaxSpiderRunning());
                    default -> assertEquals(100, spiderScanService.getSpiderScanProgressPercent(scanId));
                }
                if (jobId != null) {
                    assertEquals(ScanJobStatus.CANCELLED, store.load(jobId).orElseThrow().getStatus());
                }
                if (browserCrawl) {
                    assertFalse(stopped.contains("Continue security testing: call zap_attack_start"), stopped);
                }
            });
        } finally {
            try {
                if (operationId != null) {
                    guided.stopCrawl(operationId);
                }
            } finally {
                queue.shutdownExecutor();
            }
        }
    }

    private static Stream<Arguments> guidedCrawlerModes() {
        return Stream.of("http", "client", "browser", "auto")
                .flatMap(strategy -> Stream.of(GuidedExecutionModeResolver.ExecutionMode.values())
                        .map(mode -> Arguments.of(strategy, mode)));
    }

    private static void printBrowserVersion(String name, String executable) throws Exception {
        var result = ZAP.execInContainer(executable, "--version");
        System.out.printf("Crawler test %s (exit %d): %s %s%n", name, result.getExitCode(),
                result.getStdout().trim(), result.getStderr().trim());
    }

    private static List<String> recordedUrls(String origin) throws Exception {
        return ((ApiResponseList) clientApi.core.urls(origin)).getItems().stream()
                .map(ApiResponseElement.class::cast).map(ApiResponseElement::getValue).toList();
    }

    private static String extractResponseValue(String response, String prefix) {
        return response.lines().filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim()).findFirst().orElseThrow();
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
        // Browser authentication's auto-detected header/cookie replay depends on passive
        // session detection. Do not carry a previous duration test's backlog into login.
        System.out.println("Browser authentication fixture passive records before setup: "
                + clientApi.pscan.recordsToScan().toString(0));
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
                .until(() -> "0".equals(((ApiResponseElement) clientApi.pscan.recordsToScan()).getValue()));
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
                new ZapEngineAdapter(), new GatewayRecordFactory(), mock(GuidedToolActions.class));
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
    void clientSpiderDurationExpiresWithoutReceivingNewNodes() throws Exception {
        String previousInitialLoadTime = ((ApiResponseElement) clientApi.callApi(
                "clientSpider", "view", "optionInitialLoadTimeInSecs", Map.of())).getValue();
        String previousPageLoadTime = ((ApiResponseElement) clientApi.callApi(
                "clientSpider", "view", "optionPageLoadTimeInSecs", Map.of())).getValue();
        String durationStatistic = "stats.client.spider.event.max.time";
        String scanId = null;
        try {
            // Client 0.33 schedules the deadline independently of new-node events. A finite,
            // blank page with a longer initial wait must expire before that wait finishes.
            clientApi.callApi("clientSpider", "action", "setOptionInitialLoadTimeInSecs", Map.of("Integer", "120"));
            clientApi.callApi("clientSpider", "action", "setOptionPageLoadTimeInSecs", Map.of("Integer", "120"));
            clientApi.stats.clearStats(durationStatistic);
            scanId = clientSpiderService.startClientSpiderJob("http://direct-scan-target/client-idle.html", 1);
            String scanToExpire = scanId;
            await().atMost(Duration.ofSeconds(100)).pollInterval(Duration.ofSeconds(1))
                    .until(() -> clientSpiderService.getClientSpiderProgressPercent(scanToExpire) == 100);
            ApiResponseSet statistics = (ApiResponseSet) clientApi.stats.stats(durationStatistic);
            String expirations = statistics.getStringValue(durationStatistic);
            assertTrue(expirations != null && Long.parseLong(expirations) > 0,
                    "The idle crawl must finish because ZAP enforced its duration, without new-node activity");
        } finally {
            try {
                if (scanId != null) {
                    clientSpiderService.stopClientSpiderJob(scanId);
                }
            } finally {
                clientApi.callApi("clientSpider", "action", "setOptionInitialLoadTimeInSecs",
                        Map.of("Integer", previousInitialLoadTime));
                clientApi.callApi("clientSpider", "action", "setOptionPageLoadTimeInSecs",
                        Map.of("Integer", previousPageLoadTime));
            }
        }
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
