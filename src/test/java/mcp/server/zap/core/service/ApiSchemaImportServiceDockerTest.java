package mcp.server.zap.core.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import mcp.server.zap.core.gateway.ZapEngineApiImportAccess;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Tag("docker")
@Testcontainers
class ApiSchemaImportServiceDockerTest {
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> FIXTURES =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("api-schema-fixtures")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/openapi.yaml"), "/usr/share/nginx/html/openapi.yaml")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/schema.graphql"), "/usr/share/nginx/html/schema.graphql")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/service.wsdl"), "/usr/share/nginx/html/service.wsdl")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/graphql"), "/usr/share/nginx/html/graphql")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/soap"), "/usr/share/nginx/html/soap")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/schema.graphql"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(ZapDockerTestSupport.zapImage())
                    .withNetwork(NETWORK)
                    .dependsOn(FIXTURES)
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/openapi.yaml"), "/zap/wrk/openapi.yaml")
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
                            "openapi",
                            "-addoninstall",
                            "graphql",
                            "-addoninstall",
                            "soap"
                    )
                    .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static ClientApi clientApi;
    private static OpenApiService service;

    @BeforeAll
    static void setupService() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);
        service = new OpenApiService(new ZapEngineApiImportAccess(clientApi), mock(UrlValidationService.class));
    }

    @ParameterizedTest
    @CsvSource({
            "url, http://api-schema-fixtures/full-url, /full-url/pets",
            "file, http://api-schema-fixtures/full-file, /full-file/pets",
            "url, api-schema-fixtures/partial-url, /partial-url/pets",
            "file, api-schema-fixtures/partial-file, /partial-file/pets",
            "url, //api-schema-fixtures/relative-url, /relative-url/pets",
            "file, //api-schema-fixtures/relative-file, /relative-file/pets"
    })
    void openApiTargetOverrideControlsRealZapDestination(String sourceKind, String target, String expectedPath) {
        String response = "file".equals(sourceKind)
                ? service.importOpenApiSpecFile("/zap/wrk/openapi.yaml", target)
                : service.importOpenApiSpec("http://api-schema-fixtures/openapi.yaml", target);

        assertTrue(response.contains("OpenAPI import completed"));
        awaitImportedUrl("http://api-schema-fixtures" + expectedPath);
    }

    @Test
    void importGraphqlSchemaUrlWorksAgainstRealZap() {
        String response = service.importGraphqlSchemaUrl(
                "http://api-schema-fixtures/graphql",
                "http://api-schema-fixtures/schema.graphql"
        );

        assertTrue(response.contains("GraphQL import completed"));
        awaitImportedUrl("http://api-schema-fixtures/graphql");
    }

    @Test
    void importSoapWsdlUrlWorksAgainstRealZap() {
        String response = service.importSoapWsdlUrl("http://api-schema-fixtures/service.wsdl");

        assertTrue(response.contains("SOAP/WSDL import completed"));
        awaitImportedUrl("http://api-schema-fixtures/soap");
    }

    private static void awaitImportedUrl(String expectedUrl) {
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    List<String> seenUrls = readUrls("http://api-schema-fixtures");
                    assertTrue(
                            seenUrls.contains(expectedUrl),
                            () -> "Expected imported URL not found: " + expectedUrl + " seen=" + seenUrls
                    );
                });
    }

    private static List<String> readUrls(String baseUrl) throws Exception {
        ApiResponse response = clientApi.core.urls(baseUrl);
        List<String> urls = new ArrayList<>();
        if (response instanceof ApiResponseList list) {
            for (ApiResponse item : list.getItems()) {
                if (item instanceof ApiResponseElement element) {
                    urls.add(element.getValue());
                }
            }
        }
        return urls;
    }
}
