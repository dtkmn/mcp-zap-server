package mcp.server.zap.core.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.OpenApiContentImportProperties;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.ImportResult;
import mcp.server.zap.core.gateway.ZapEngineApiImportAccess;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.Yaml;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("docker")
@Testcontainers
class OpenApiContentImportServiceDockerTest {
    private static final String CLIENT_ID = "content-docker-client";
    private static final String WORKSPACE_ID = "content-docker-workspace";
    private static final String SOURCE_MARKER = "attached-definition-private-marker";
    private static final String CONTAINER_ROOT = "/mcp-import-fixture";
    private static final String CONTAINER_STAGING = CONTAINER_ROOT + "/content-staging";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SHARED_ROOT = createSharedRoot();
    private static final Path STAGING_ROOT = SHARED_ROOT.resolve("content-staging");
    private static final String HOST_UID = unixIdentity("uid");
    private static final String HOST_GID = unixIdentity("gid");
    private static final Network NETWORK = Network.newNetwork();
    private static final String FIXTURE_CONFIG = """
            server {
                listen 80;
                location / {
                    default_type application/json;
                    return 200 '{}';
                }
            }
            """;

    @Container
    static final GenericContainer<?> ALLOWED = fixture("content-import-allowed");

    @Container
    static final GenericContainer<?> FORBIDDEN = fixture("content-import-forbidden");

    @Container
    static final GenericContainer<?> ZAP = new GenericContainer<>(ZapDockerTestSupport.zapImage())
            .withNetwork(NETWORK)
            .dependsOn(ALLOWED, FORBIDDEN)
            .withFileSystemBind(SHARED_ROOT.toString(), CONTAINER_ROOT, BindMode.READ_WRITE)
            // Match the host writer without weakening the staging directory or file permissions.
            .withCreateContainerCmdModifier(cmd -> cmd.withUser(HOST_UID + ":" + HOST_GID))
            .withEnv("HOME", CONTAINER_ROOT + "/zap-home")
            .withEnv("JAVA_TOOL_OPTIONS", "-Duser.home=" + CONTAINER_ROOT + "/zap-home")
            .withExposedPorts(8090)
            .withCommand("zap.sh", "-daemon", "-host", "0.0.0.0", "-port", "8090",
                    "-dir", CONTAINER_ROOT + "/zap-home",
                    "-config", "api.disablekey=true",
                    "-config", "api.addrs.addr.name=.*",
                    "-config", "api.addrs.addr.regex=true",
                    "-addoninstall", "openapi")
            .waitingFor(ZapDockerTestSupport.waitForZapPort());

    private static final AtomicInteger FILE_IMPORT_CALLS = new AtomicInteger();
    private static final AtomicReference<Path> LAST_STAGED_FILE = new AtomicReference<>();
    private static final AtomicReference<ImportResult> LAST_IMPORT_RESULT = new AtomicReference<>();
    private static ClientApi clientApi;
    private static OpenApiContentImportService service;
    private static String allowedBase;
    private static String forbiddenBase;

    @BeforeAll
    static void setupService() throws Exception {
        clientApi = ZapDockerTestSupport.clientApi(ZAP.getHost(), ZAP.getMappedPort(8090));
        ZapDockerTestSupport.awaitZapApiReady(clientApi);
        assertEquals(HOST_UID, ZAP.execInContainer("id", "-u").getStdout().trim());
        assertEquals(HOST_GID, ZAP.execInContainer("id", "-g").getStdout().trim());

        String allowedIp = networkIp(ALLOWED);
        allowedBase = "http://" + allowedIp;
        forbiddenBase = "http://" + networkIp(FORBIDDEN);
        service = contentService(new ObservedImportAccess(clientApi), CONTAINER_STAGING);
        service.initialize();
    }

    @BeforeEach
    void setWorkspaceIdentity() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CLIENT_ID, "unused", List.of()));
        FILE_IMPORT_CALLS.set(0);
        LAST_STAGED_FILE.set(null);
        LAST_IMPORT_RESULT.set(null);
    }

    @AfterEach
    void clearWorkspaceIdentityAndRemoveFixtureDefinitions() throws Exception {
        SecurityContextHolder.clearContext();
        // Tests assert production cleanup before this hook; also isolate the next case if an assertion fails.
        try (var directories = Files.newDirectoryStream(STAGING_ROOT, "import-*")) {
            for (Path directory : directories) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} {1} confines embedded destinations to the selected target")
    @CsvSource({"3.0.3,json", "3.0.3,yaml", "3.1.0,json", "3.1.0,yaml", "2.0,json", "2.0,yaml"})
    void contentImportsRewriteRealZapDestinations(String version, String format) throws Exception {
        String target = allowedBase + "/selected-v" + version.replace('.', '-') + "-" + format;
        String response = service.importContent(serialize(definition(version), format), target);

        assertTrue(response.contains("completed with no reported import warnings"), response);
        assertEquals(List.of(), LAST_IMPORT_RESULT.get().values());
        awaitImportedUrls(target, "/root-pets", "/path-pets", "/operation-pets");
        assertCompletedImportIsPrivateAndClean(response);
        assertForbiddenWasNotContacted();
    }

    @ParameterizedTest(name = "{0} accepts an internal cyclic schema without changing destinations")
    @CsvSource({"3.0.3,json", "3.1.0,yaml", "2.0,json"})
    void internalCyclicSchemaReferencesWorkAgainstRealZap(String version, String format) throws Exception {
        Map<String, Object> definition = definition(version);
        String reference = version.equals("2.0") ? "#/definitions/Node" : "#/components/schemas/Node";
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("name", Map.of("type", "string"),
                "child", Map.of("$ref", reference)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("description", "Cyclic schema fixture");
        if (version.equals("2.0")) {
            definition.put("definitions", Map.of("Node", schema));
            response.put("schema", Map.of("$ref", reference));
        } else {
            definition.put("components", Map.of("schemas", Map.of("Node", schema)));
            response.put("content", Map.of("application/json", Map.of("schema", Map.of("$ref", reference))));
        }
        definition.put("paths", Map.of("/recursive-nodes", Map.of("get",
                Map.of("responses", Map.of("200", response)))));
        String target = allowedBase + "/cyclic-v" + version.replace('.', '-');
        String result = service.importContent(serialize(definition, format), target);

        assertTrue(result.startsWith("OpenAPI content import"), result);
        assertFalse(result.contains("unconfirmed"), result);
        awaitImportedUrls(target, "/recursive-nodes");
        assertCompletedImportIsPrivateAndClean(result);
        assertForbiddenWasNotContacted();
    }

    @Test
    void blockedExplicitTargetDoesNotStageOrReachRealZap() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.importContent(
                serialize(definition("3.0.3"), "json"), forbiddenBase + "/blocked-explicit"));

        assertEquals(0, FILE_IMPORT_CALLS.get());
        assertNull(LAST_STAGED_FILE.get());
        assertNoStagedDefinitions();
        assertForbiddenWasNotContacted();
        assertFalse(FORBIDDEN.getLogs().contains(" /blocked-explicit"));
    }

    @Test
    void incorrectSharedMountMappingFailsDefinitivelyAndDeletesStagedDefinition() throws Exception {
        String incorrectMapping = CONTAINER_ROOT + "/nonexistent-staging";
        String target = allowedBase + "/missing-shared-mapping";
        assertTrue(ZAP.execInContainer("test", "-d", incorrectMapping).getExitCode() != 0);
        service.close();
        OpenApiContentImportService incorrectlyMapped = null;
        try {
            incorrectlyMapped = contentService(new ZapEngineApiImportAccess(clientApi), incorrectMapping);
            incorrectlyMapped.initialize();
            OpenApiContentImportService activeService = incorrectlyMapped;
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                    activeService.importContent(serialize(definition("3.0.3"), "json"), target));

            assertTrue(failure.getMessage().contains("ZAP could not read the staged definition"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Import did not start"), failure.getMessage());
            assertFalse(failure.getMessage().contains("unconfirmed"));
            for (String withheld : List.of(SOURCE_MARKER, STAGING_ROOT.toString(), incorrectMapping, forbiddenBase)) {
                assertFalse(failure.getMessage().contains(withheld), "Mapping errors must withhold content and internal paths");
            }
            assertNoStagedDefinitions();
            assertTrue(readUrls(target).isEmpty(), "An unreadable definition must not import any selected endpoints");
            assertFalse(ALLOWED.getLogs().contains(" /missing-shared-mapping"));
            assertForbiddenWasNotContacted();
        } finally {
            if (incorrectlyMapped != null) {
                incorrectlyMapped.close();
            }
            service.initialize();
        }
    }

    private static OpenApiContentImportService contentService(EngineApiImportAccess engine, String zapDirectory) {
        UrlValidationService policy = new UrlValidationService();
        ReflectionTestUtils.setField(policy, "allowPrivateNetworks", true);
        ReflectionTestUtils.setField(policy, "whitelist", List.of(networkIp(ALLOWED)));
        ReflectionTestUtils.setField(policy, "blacklist", List.of());

        ApiKeyProperties clients = new ApiKeyProperties();
        ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
        client.setClientId(CLIENT_ID);
        client.setWorkspaceId(WORKSPACE_ID);
        clients.setApiKeys(List.of(client));

        OpenApiContentImportProperties properties = new OpenApiContentImportProperties();
        properties.setEnabled(true);
        properties.setLocalDirectory(STAGING_ROOT.toString());
        properties.setZapDirectory(zapDirectory);
        return new OpenApiContentImportService(engine, policy, properties, new ClientWorkspaceResolver(clients));
    }

    private static class ObservedImportAccess extends ZapEngineApiImportAccess {
        ObservedImportAccess(ClientApi clientApi) {
            super(clientApi);
        }

        @Override
        public ImportResult importOpenApiFile(FileImportRequest request) {
            FILE_IMPORT_CALLS.incrementAndGet();
            try {
                Path zapFile = Path.of(request.filePath());
                assertTrue(zapFile.startsWith(Path.of(CONTAINER_STAGING)));
                Path localFile = STAGING_ROOT.resolve(Path.of(CONTAINER_STAGING).relativize(zapFile));
                LAST_STAGED_FILE.set(localFile);
                assertTrue(Files.isRegularFile(localFile));
                String workspaceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(WORKSPACE_ID.getBytes(StandardCharsets.UTF_8)));
                assertTrue(localFile.getParent().getFileName().toString()
                        .matches("import-" + workspaceHash + "-[0-9a-f-]{36}"));
                assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(localFile.getParent()));
                assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(localFile));
                String normalized = Files.readString(localFile);
                JsonNode document = JSON.readTree(normalized);
                URI target = URI.create(request.hostOverride());
                if ("2.0".equals(document.path("swagger").asString())) {
                    assertEquals(target.getScheme(), document.path("schemes").path(0).asString());
                    assertEquals(target.getRawAuthority(), document.path("host").asString());
                    assertEquals(target.getRawPath(), document.path("basePath").asString());
                } else {
                    assertEquals(request.hostOverride(), document.path("servers").path(0).path("url").asString());
                }
                assertFalse(normalized.contains(URI.create(forbiddenBase).getHost()));
                var readCheck = ZAP.execInContainer("test", "-r", request.filePath());
                assertEquals(0, readCheck.getExitCode(), readCheck.getStderr());
                var digest = ZAP.execInContainer("sha256sum", request.filePath());
                assertEquals(0, digest.getExitCode(), digest.getStderr());
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(localFile))),
                        digest.getStdout().trim().split("\\s+", 2)[0]);
            } catch (Exception e) {
                throw new AssertionError("Staged definition must be readable unchanged through the live ZAP bind mount", e);
            }
            ImportResult result = super.importOpenApiFile(request);
            LAST_IMPORT_RESULT.set(result);
            return result;
        }
    }

    private static Map<String, Object> definition(String version) {
        Map<String, Object> definition = new LinkedHashMap<>();
        boolean swagger = version.equals("2.0");
        definition.put(swagger ? "swagger" : "openapi", version);
        definition.put("info", Map.of("title", "Attached content fixture", "version", "1.0.0", "description", SOURCE_MARKER));
        if (swagger) {
            definition.put("host", forbiddenBase.substring("http://".length()));
            definition.put("schemes", List.of("https"));
            definition.put("basePath", "/embedded-swagger");
        } else {
            definition.put("servers", servers(forbiddenBase + "/embedded-root"));
        }
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/root-pets", Map.of("get", operation()));
        Map<String, Object> pathItem = new LinkedHashMap<>();
        pathItem.put("get", operation());
        if (!swagger) {
            pathItem.put("servers", servers(forbiddenBase + "/embedded-path"));
        }
        paths.put("/path-pets", pathItem);
        Map<String, Object> overriddenOperation = operation();
        if (swagger) {
            overriddenOperation.put("schemes", List.of("https"));
        } else {
            overriddenOperation.put("servers", servers(forbiddenBase + "/embedded-operation"));
        }
        paths.put("/operation-pets", Map.of("get", overriddenOperation));
        definition.put("paths", paths);
        return definition;
    }

    private static Map<String, Object> operation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("responses", Map.of("200", Map.of("description", "Fixture response")));
        return operation;
    }

    private static List<Map<String, String>> servers(String url) {
        return List.of(Map.of("url", url));
    }

    private static String serialize(Map<String, Object> definition, String format) throws Exception {
        return format.equals("yaml") ? new Yaml().dump(definition) : JSON.writeValueAsString(definition);
    }

    private static void assertCompletedImportIsPrivateAndClean(String response) throws Exception {
        assertEquals(1, FILE_IMPORT_CALLS.get());
        assertNotNull(LAST_IMPORT_RESULT.get());
        assertNotNull(LAST_STAGED_FILE.get());
        assertFalse(Files.exists(LAST_STAGED_FILE.get().getParent()));
        assertNoStagedDefinitions();
        for (String withheld : List.of(SOURCE_MARKER, STAGING_ROOT.toString(), CONTAINER_STAGING, forbiddenBase)) {
            assertFalse(response.contains(withheld), "Import response must not expose submitted data or internal paths");
        }
    }

    private static void assertNoStagedDefinitions() throws Exception {
        try (var directories = Files.newDirectoryStream(STAGING_ROOT, "import-*")) {
            assertFalse(directories.iterator().hasNext(), "Completed or rejected imports must not leave definitions staged");
        }
    }

    private static void assertForbiddenWasNotContacted() throws Exception {
        assertTrue(readUrls(forbiddenBase).isEmpty(), "Embedded destinations must not appear in the ZAP session");
        assertFalse(FORBIDDEN.getLogs().contains(" /embedded-"), "The reachable forbidden fixture must not receive definition-directed requests");
    }

    private static void awaitImportedUrls(String target, String... paths) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            List<String> imported = readUrls(target);
            String requests = ALLOWED.getLogs();
            for (String path : paths) {
                assertTrue(imported.contains(target + path), () -> "Missing imported URL " + target + path + "; seen=" + imported);
                assertTrue(requests.contains(" " + URI.create(target).getRawPath() + path + " "),
                        () -> "The allowed fixture did not receive the selected target path " + target + path);
            }
        });
    }

    private static List<String> readUrls(String base) throws Exception {
        ApiResponse response = clientApi.core.urls(base);
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

    private static GenericContainer<?> fixture(String alias) {
        return new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                .withNetwork(NETWORK).withNetworkAliases(alias).withExposedPorts(80)
                .withCopyToContainer(Transferable.of(FIXTURE_CONFIG), "/etc/nginx/conf.d/default.conf")
                .waitingFor(Wait.forHttp("/"));
    }

    private static String networkIp(GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().iterator().next().getIpAddress();
    }

    private static Path createSharedRoot() {
        try {
            Path root = Files.createTempDirectory("zap-content-import-docker-").toRealPath();
            Files.createDirectory(root.resolve("content-staging"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));
            Files.createDirectory(root.resolve("zap-home"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Docker content-import tests require a dedicated POSIX workspace", e);
        }
    }

    private static String unixIdentity(String attribute) {
        try {
            return Files.getAttribute(SHARED_ROOT, "unix:" + attribute).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Docker content-import tests require the host writer's numeric UID/GID", e);
        }
    }

    @AfterAll
    static void closeSharedWorkspace() throws Exception {
        if (service != null) {
            service.close();
        }
        ZAP.stop();
        ALLOWED.stop();
        FORBIDDEN.stop();
        NETWORK.close();
        try (var paths = Files.walk(SHARED_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
