package mcp.server.zap.core.configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.ImportResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcp.server.tools.surface=guided",
                "mcp.server.security.enabled=true",
                "mcp.server.security.mode=api-key",
                "mcp.server.security.authorization.mode=enforce",
                "mcp.server.auth.apiKeys[0].clientId=content-importer",
                "mcp.server.auth.apiKeys[0].workspaceId=content-import-workspace",
                "mcp.server.auth.apiKeys[0].key=content-import-key",
                "mcp.server.auth.apiKeys[0].scopes[0]=mcp:tools:list",
                "mcp.server.auth.apiKeys[0].scopes[1]=zap:api:import",
                "mcp.server.auth.apiKeys[1].clientId=content-no-import",
                "mcp.server.auth.apiKeys[1].workspaceId=content-no-import-workspace",
                "mcp.server.auth.apiKeys[1].key=content-no-import-key",
                "mcp.server.auth.apiKeys[1].scopes[0]=mcp:tools:list",
                "mcp.server.protection.enabled=false",
                "zap.scan.url.validation.enabled=true",
                "zap.scan.url.allowLocalhost=true",
                "zap.scan.url.whitelist=127.0.0.1",
                "zap.scan.url.blacklist="
        }
)
@ActiveProfiles("test")
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpOpenApiContentImportIntegrationTest extends AbstractMcpProtectionIntegrationTest {
    private static final String IMPORT_KEY = "content-import-key";
    private static final String NO_IMPORT_KEY = "content-no-import-key";
    private static final String WORKSPACE_ID = "content-import-workspace";
    private static final String TOOL_NAME = "zap_target_import";
    private static final String TARGET = "http://127.0.0.1:8080/selected-api";
    private static final String PRIVATE_MARKER = "private-attached-definition-content";
    private static final String EXTERNAL_REF = "https://unreachable.invalid/private-ref.yaml?token=" + PRIVATE_MARKER;
    private static final String ZAP_ROOT = "/zap/content-integration";
    private static final Path CONTENT_ROOT = createContentRoot();

    @MockitoBean
    private EngineApiImportAccess engine;

    @DynamicPropertySource
    static void contentImportProperties(DynamicPropertyRegistry registry) {
        registry.add("zap.openapi.content-import.enabled", () -> true);
        registry.add("zap.openapi.content-import.local-directory", CONTENT_ROOT::toString);
        registry.add("zap.openapi.content-import.zap-directory", () -> ZAP_ROOT);
    }

    @Test
    void authenticatedToolCallBindsContentAndStagesNormalizedDefinitionInItsWorkspace() throws Exception {
        String session = initializeSession(IMPORT_KEY);
        EntityExchangeResult<String> listing = listTools(IMPORT_KEY, session);
        assertThat(listing.getStatus().value()).isEqualTo(200);
        JsonNode importTool = null;
        for (JsonNode tool : responseEnvelope(listing).path("result").path("tools")) {
            if (TOOL_NAME.equals(tool.path("name").asString())) {
                importTool = tool;
            }
        }
        if (importTool == null) {
            throw new AssertionError("Missing tool: " + TOOL_NAME);
        }
        for (String argument : List.of("sourceKind", "source", "hostOverride")) {
            assertThat(importTool.path("inputSchema").path("properties").path(argument).path("type").asString())
                    .isEqualTo("string");
        }

        AtomicReference<Path> stagedFile = new AtomicReference<>();
        when(engine.importOpenApiFile(any(FileImportRequest.class))).thenAnswer(invocation -> {
            FileImportRequest request = invocation.getArgument(0);
            assertThat(request.hostOverride()).isEqualTo(TARGET);
            Path zapFile = Path.of(request.filePath());
            assertThat(zapFile.startsWith(Path.of(ZAP_ROOT))).isTrue();
            Path localFile = CONTENT_ROOT.resolve(Path.of(ZAP_ROOT).relativize(zapFile));
            stagedFile.set(localFile);
            String workspaceHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(WORKSPACE_ID.getBytes(StandardCharsets.UTF_8)));
            assertThat(localFile.getParent().getFileName().toString())
                    .matches("import-" + workspaceHash + "-[0-9a-f-]{36}");
            assertThat(localFile.getFileName().toString()).isEqualTo("definition.json");
            assertThat(Files.getPosixFilePermissions(localFile)).isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
            String staged = Files.readString(localFile);
            assertThat(staged).startsWith("{").contains(PRIVATE_MARKER).doesNotContain("embedded.invalid");
            JsonNode document = OBJECT_MAPPER.readTree(staged);
            assertThat(document.path("servers").path(0).path("url").asString()).isEqualTo(TARGET);
            assertThat(document.path("paths").path("/pets").path("servers").path(0).path("url").asString())
                    .isEqualTo(TARGET);
            assertThat(document.path("paths").path("/pets").path("get").path("servers").path(0).path("url").asString())
                    .isEqualTo(TARGET);
            return new ImportResult(List.of());
        });

        EntityExchangeResult<String> result = callTool(IMPORT_KEY, session, TOOL_NAME, arguments(definition(false)));

        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode envelope = responseEnvelope(result);
        assertThat(envelope.path("result").path("isError").asBoolean()).isFalse();
        assertThat(envelope.path("result").path("content").path(0).path("text").asString())
                .contains("Source Kind: content", "contents withheld", "completed with no reported import warnings");
        assertPrivateResponse(result);
        verify(engine).importOpenApiFile(any(FileImportRequest.class));
        assertThat(stagedFile.get()).isNotNull();
        assertThat(Files.exists(stagedFile.get().getParent())).isFalse();
    }

    @Test
    void callerWithoutImportScopeIsRejectedBeforeStagingOrEngineDispatch() throws Exception {
        String session = initializeSession(NO_IMPORT_KEY);

        EntityExchangeResult<String> result = callTool(NO_IMPORT_KEY, session, TOOL_NAME, arguments(definition(false)));

        assertThat(result.getStatus().value()).isEqualTo(403);
        assertThat(result.getResponseBody()).contains("insufficient_scope");
        assertPrivateResponse(result);
        verifyNoInteractions(engine);
    }

    @Test
    void externalReferenceReturnsSanitizedToolErrorWithoutStagingOrEngineDispatch() throws Exception {
        String session = initializeSession(IMPORT_KEY);

        EntityExchangeResult<String> result = callTool(IMPORT_KEY, session, TOOL_NAME, arguments(definition(true)));

        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode envelope = responseEnvelope(result);
        assertThat(envelope.path("result").path("isError").asBoolean()).isTrue();
        assertThat(envelope.path("result").path("content").path(0).path("text").asString())
                .contains("OpenAPI").doesNotContain(EXTERNAL_REF);
        assertPrivateResponse(result);
        verifyNoInteractions(engine);
    }

    @AfterEach
    void noDefinitionsRemainStaged() throws Exception {
        try (var directories = Files.newDirectoryStream(CONTENT_ROOT, "import-*")) {
            assertThat(directories.iterator().hasNext()).isFalse();
        }
    }

    private static Map<String, Object> arguments(String source) {
        return Map.of("definitionType", "openapi", "sourceKind", "content", "source", source,
                "hostOverride", TARGET);
    }

    private static String definition(boolean externalReference) throws Exception {
        Map<String, Object> response = externalReference
                ? Map.of("$ref", EXTERNAL_REF)
                : Map.of("description", "Fixture response");
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "openapi", "3.1.0",
                "info", Map.of("title", "Attached fixture", "version", "1.0.0", "description", PRIVATE_MARKER),
                "servers", List.of(Map.of("url", "http://embedded.invalid/root")),
                "paths", Map.of("/pets", Map.of(
                        "servers", List.of(Map.of("url", "http://embedded.invalid/path")),
                        "get", Map.of("servers", List.of(Map.of("url", "http://embedded.invalid/operation")),
                                "responses", Map.of("200", response))))
        ));
    }

    private static void assertPrivateResponse(EntityExchangeResult<String> result) {
        assertThat(result.getResponseBody()).doesNotContain(PRIVATE_MARKER, EXTERNAL_REF,
                CONTENT_ROOT.toString(), ZAP_ROOT, "embedded.invalid");
    }

    private JsonNode responseEnvelope(EntityExchangeResult<String> result) throws Exception {
        String body = result.getResponseBody();
        assertThat(body).isNotBlank();
        MediaType contentType = result.getResponseHeaders().getContentType();
        if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            List<String> messages = Arrays.stream(body.split("\\r?\\n\\r?\\n"))
                    .map(event -> event.lines().filter(line -> line.startsWith("data:"))
                            .map(line -> line.substring(5).stripLeading())
                            .collect(java.util.stream.Collectors.joining("\n")))
                    .filter(data -> !data.isBlank()).toList();
            assertThat(messages).hasSize(1);
            return OBJECT_MAPPER.readTree(messages.get(0));
        }
        return OBJECT_MAPPER.readTree(body);
    }

    private static Path createContentRoot() {
        try {
            Path root = Files.createTempDirectory("mcp-content-import-integration-").toRealPath();
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("Content-import integration tests require a dedicated POSIX directory", e);
        }
    }

    @AfterAll
    void removeContentRoot() throws Exception {
        // POSIX permits unlinking the lock file; @DirtiesContext closes its owner after this hook.
        try (var paths = Files.walk(CONTENT_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
