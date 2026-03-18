package mcp.server.zap.core.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import org.zaproxy.clientapi.core.ClientApiException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
public class ApiSchemaImportServiceDockerTest {
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> FIXTURES =
            new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("api-schema-fixtures")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/schema.graphql"), "/usr/share/nginx/html/schema.graphql")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/service.wsdl"), "/usr/share/nginx/html/service.wsdl")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/graphql"), "/usr/share/nginx/html/graphql")
                    .withCopyToContainer(MountableFile.forClasspathResource("api-schema/soap"), "/usr/share/nginx/html/soap")
                    .withExposedPorts(80)
                    .waitingFor(Wait.forHttp("/schema.graphql"));

    @Container
    static final GenericContainer<?> ZAP =
            new GenericContainer<>(DockerImageName.parse("zaproxy/zap-stable:2.17.0"))
                    .withNetwork(NETWORK)
                    .dependsOn(FIXTURES)
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
                            "graphql",
                            "-addoninstall",
                            "soap"
                    )
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2));

    private static ClientApi clientApi;
    private static OpenApiService service;

    @BeforeAll
    static void setupService() throws Exception {
        clientApi = new ClientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        awaitApiReady();
        service = new OpenApiService(clientApi, mock(UrlValidationService.class));
    }

    @Test
    void importGraphqlSchemaUrlWorksAgainstRealZap() throws Exception {
        String response = service.importGraphqlSchemaUrl(
                "http://api-schema-fixtures/graphql",
                "http://api-schema-fixtures/schema.graphql"
        );

        assertTrue(response.contains("GraphQL import completed"));
        awaitImportedUrl("http://api-schema-fixtures/graphql");
    }

    @Test
    void importSoapWsdlUrlWorksAgainstRealZap() throws Exception {
        String response = service.importSoapWsdlUrl("http://api-schema-fixtures/service.wsdl");

        assertTrue(response.contains("SOAP/WSDL import completed"));
        awaitImportedUrl("http://api-schema-fixtures/soap");
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

    private static void awaitImportedUrl(String expectedUrl) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> seenUrls = List.of();
        while (System.nanoTime() < deadline) {
            seenUrls = readUrls("http://api-schema-fixtures");
            if (seenUrls.stream().anyMatch(url -> url.equals(expectedUrl))) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Expected imported URL not found: " + expectedUrl + " seen=" + seenUrls);
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
