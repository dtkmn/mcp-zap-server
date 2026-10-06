package mcp.server.zap.core.service;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * Checks HTTP crawl traversal against a finite local fixture, without active scans or browsers.
 */
@Tag("docker")
@Testcontainers
class SpiderScanLimitsDockerTest {
    private static final String ORIGIN = "http://spider-limits-target";
    private static final String ROOT_PATH = "/crawl/";
    private static final String FIRST_CHILD = "/crawl/one/";
    private static final String SECOND_LEVEL = "/crawl/one/deeper/";
    private static final String CHILD_ROOT_PATH = "/children/";
    private static final String CHILD_FIRST_PATH = "/children/one/";
    private static final String CHILD_DEEPER_PATH = "/children/one/deeper/";
    private static final String CHILD_SECOND_PATH = "/children/two/";
    private static final String CHILD_THIRD_PATH = "/children/three/";
    private static final String FIXTURE_MARKER = "HTTP crawl limit fixture";
    // Match the other Docker tests: internal-only networks cannot publish the ZAP API port.
    // Fixture HTML has only finite, same-origin links; these tests launch no external scans.
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> TARGET = new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("spider-limits-target")
            .withCopyToContainer(Transferable.of(page("level 0", """
                    <a href="/crawl/one/">First child</a>
                    <a href="/crawl/two/">Second child</a>
                    <a href="/crawl/three/">Third child</a>
                    """)), "/usr/share/nginx/html/crawl/index.html")
            .withCopyToContainer(Transferable.of(page("level 1", """
                    <a href="/crawl/one/deeper/">Second level</a>
                    """)), "/usr/share/nginx/html/crawl/one/index.html")
            .withCopyToContainer(Transferable.of(page("level 2", "")),
                    "/usr/share/nginx/html/crawl/one/deeper/index.html")
            .withCopyToContainer(Transferable.of(page("second sibling", "")),
                    "/usr/share/nginx/html/crawl/two/index.html")
            .withCopyToContainer(Transferable.of(page("third sibling", "")),
                    "/usr/share/nginx/html/crawl/three/index.html")
            // Discover siblings in stages so the Sites tree grows before each next link is parsed.
            .withCopyToContainer(Transferable.of(page("child limit root", """
                    <a href="/children/one/">First child</a>
                    """)), "/usr/share/nginx/html/children/index.html")
            .withCopyToContainer(Transferable.of(page("child limit first sibling", """
                    <a href="/children/one/deeper/">Second level</a>
                    <a href="/children/two/">Second sibling</a>
                    """)), "/usr/share/nginx/html/children/one/index.html")
            .withCopyToContainer(Transferable.of(page("child limit level 2", "")),
                    "/usr/share/nginx/html/children/one/deeper/index.html")
            .withCopyToContainer(Transferable.of(page("child limit second sibling", """
                    <a href="/children/three/">Third sibling</a>
                    """)), "/usr/share/nginx/html/children/two/index.html")
            .withCopyToContainer(Transferable.of(page("child limit third sibling", "")),
                    "/usr/share/nginx/html/children/three/index.html")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp(ROOT_PATH));

    @Container
    static final GenericContainer<?> ZAP = new GenericContainer<>(ZapDockerTestSupport.zapImage())
            .withNetwork(NETWORK)
            .dependsOn(TARGET)
            .withExposedPorts(8090)
            .withCommand(
                    "zap.sh", "-daemon", "-host", "0.0.0.0", "-port", "8090",
                    "-config", "api.disablekey=true",
                    "-config", "api.addrs.addr.name=.*",
                    "-config", "api.addrs.addr.regex=true"
            )
            .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static ClientApi clientApi;
    private SpiderScanService spiderScanService;
    private ScanLimitProperties limits;

    @BeforeAll
    static void awaitEngine() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);
    }

    @BeforeEach
    void prepareCleanFiniteCrawl() throws Exception {
        // Prevent earlier tests' Sites tree entries from becoming additional recursive seeds.
        clientApi.core.newSession(null, "true");
        clientApi.spider.setOptionProcessForm(false);
        clientApi.spider.setOptionPostForm(false);
        clientApi.spider.setOptionParseComments(false);
        clientApi.spider.setOptionParseRobotsTxt(false);
        clientApi.spider.setOptionParseSitemapXml(false);
        clientApi.spider.setOptionParseGit(false);
        clientApi.spider.setOptionParseSVNEntries(false);
        clientApi.spider.setOptionParseDsStore(false);
        clientApi.spider.disableAllDomainsAlwaysInScope();
        limits = new ScanLimitProperties();
        limits.setSpiderThreadCount(1);
        limits.setMaxSpiderScanDurationInMins(1);
        spiderScanService = new SpiderScanService(
                new ZapEngineScanExecution(clientApi), mock(UrlValidationService.class), limits);
    }

    @Test
    void shallowDepthStopsAtFirstLevelWithoutLimitingSiblingBreadth() throws Exception {
        Map<String, String> responses = crawlAndReadSuccessfulResponses(1, 0);

        assertThat(responses).containsKeys(ROOT_PATH, FIRST_CHILD, "/crawl/two/", "/crawl/three/");
        assertThat(responses).doesNotContainKey(SECOND_LEVEL);
        assertThat(responses.get(FIRST_CHILD)).contains("level 1");
    }

    @Test
    void unlimitedDepthVisitsSecondLevelOfTheFiniteFixture() throws Exception {
        Map<String, String> responses = crawlAndReadSuccessfulResponses(0, 0);

        assertThat(responses).containsOnlyKeys(ROOT_PATH, FIRST_CHILD, SECOND_LEVEL, "/crawl/two/", "/crawl/three/");
        assertThat(responses.get(SECOND_LEVEL)).contains("level 2");
    }

    @Test
    void childLimitStopsStagedSiblingDiscoveryWithoutChangingUnlimitedDepth() throws Exception {
        Map<String, String> responses = crawlAndReadSuccessfulResponses(CHILD_ROOT_PATH, 0, 2);

        // ZAP counts Sites tree nodes, including the directory's own GET node. It checks
        // the parent count before parsing, so a child cap is not an exact request quota.
        assertThat(responses).containsOnlyKeys(CHILD_ROOT_PATH, CHILD_FIRST_PATH, CHILD_DEEPER_PATH, CHILD_SECOND_PATH);
        assertThat(responses).doesNotContainKey(CHILD_THIRD_PATH);
        assertThat(responses.get(CHILD_DEEPER_PATH)).contains("level 2");
    }

    @Test
    void unlimitedChildrenContinueStagedSiblingDiscovery() throws Exception {
        Map<String, String> responses = crawlAndReadSuccessfulResponses(CHILD_ROOT_PATH, 0, 0);

        assertThat(responses).containsOnlyKeys(
                CHILD_ROOT_PATH, CHILD_FIRST_PATH, CHILD_DEEPER_PATH, CHILD_SECOND_PATH, CHILD_THIRD_PATH);
    }

    private Map<String, String> crawlAndReadSuccessfulResponses(int depth, int children) throws Exception {
        return crawlAndReadSuccessfulResponses(ROOT_PATH, depth, children);
    }

    private Map<String, String> crawlAndReadSuccessfulResponses(String rootPath, int depth, int children) throws Exception {
        limits.setSpiderMaxDepth(depth);
        limits.setSpiderMaxChildren(children);
        String scanId = spiderScanService.startSpiderScanJob(ORIGIN + rootPath);
        try {
            assertThat(((ApiResponseElement) clientApi.spider.optionMaxDepth()).getValue())
                    .isEqualTo(Integer.toString(depth));
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250))
                    .until(() -> spiderScanService.getSpiderScanProgressPercent(scanId) == 100);
            ApiResponseList messages = (ApiResponseList) clientApi.core.messages(ORIGIN + rootPath, "0", "100");
            assertThat(messages.getItems()).hasSizeLessThan(100);
            Map<String, String> responses = new LinkedHashMap<>();
            for (var item : messages.getItems()) {
                ApiResponseSet message = (ApiResponseSet) item;
                String request = message.getStringValue("requestHeader");
                assertThat(request).startsWith("GET ");
                assertThat(message.getStringValue("requestBody")).isEmpty();
                URI requestedUri = URI.create(ORIGIN).resolve(request.split(" ", 3)[1]);
                assertThat(requestedUri.getHost()).isEqualTo("spider-limits-target");
                String responseHeader = message.getStringValue("responseHeader");
                if (responseHeader.matches("(?s)^HTTP/\\S+ 200(?:\\s.*)?")) {
                    String body = message.getStringValue("responseBody");
                    assertThat(body).contains(FIXTURE_MARKER);
                    responses.put(requestedUri.getPath(), body);
                }
            }
            assertThat(responses).containsKey(rootPath);
            return responses;
        } finally {
            spiderScanService.stopSpiderScanJob(scanId);
        }
    }

    private static String page(String label, String links) {
        return "<!doctype html><html><body><h1>" + FIXTURE_MARKER + ": " + label
                + "</h1>" + links + "</body></html>";
    }
}
