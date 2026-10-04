package mcp.server.zap.core.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mcp.server.zap.core.configuration.OpenApiContentImportProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenApiContentImportServiceTest {
    private static final String CONTENT = """
            {"openapi":"3.0.3","info":{"title":"private-definition","version":"1"},
             "paths":{"/pets":{"get":{"responses":{"200":{"description":"ok"}}}}}}
            """;
    private static final String TARGET = "https://api.example.com/v1";
    @TempDir Path temp;
    private Path root;
    private EngineApiImportAccess engine;
    private UrlValidationService policy;
    private ClientWorkspaceResolver workspace;
    private OpenApiContentImportProperties properties;
    private OpenApiContentImportService service;
    private final MutableClock clock = new MutableClock();
    private final List<OpenApiContentImportService> services = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        root = temp.toRealPath().resolve("content");
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        engine = mock(EngineApiImportAccess.class);
        policy = mock(UrlValidationService.class);
        workspace = mock(ClientWorkspaceResolver.class);
        when(workspace.resolveCurrentWorkspaceId()).thenReturn("workspace-a");
        when(engine.importOpenApiFile(any())).thenReturn(new EngineApiImportAccess.ImportResult(List.of()));
        properties = new OpenApiContentImportProperties();
        properties.setEnabled(true);
        properties.setLocalDirectory(root.toString());
        properties.setZapDirectory("/zap/content-test");
        service = createService();
    }

    @AfterEach
    void close() {
        services.forEach(OpenApiContentImportService::close);
    }

    @Test
    void stagesThroughDistinctEnginePathWithPrivatePermissionsAndDeletesAfterReturn() {
        when(engine.importOpenApiFile(any())).thenAnswer(invocation -> {
            EngineApiImportAccess.FileImportRequest request = invocation.getArgument(0);
            assertThat(request.filePath()).startsWith("/zap/content-test/import-").endsWith("/definition.json");
            assertThat(request.hostOverride()).isEqualTo(TARGET);
            Path file = localFile(request.filePath());
            assertThat(Files.readString(file)).contains("private-definition", TARGET);
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
            assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwxr-x---"));
            assertThat(Files.getAttribute(file, "posix:group")).isEqualTo(Files.getAttribute(root, "posix:group"));
            return new EngineApiImportAccess.ImportResult(List.of());
        });

        assertThat(service.importContent(CONTENT, TARGET)).contains("completed with no reported import warnings")
                .doesNotContain("private-definition", root.toString(), "/zap/content-test");
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void warningResponseIsPartialAndCannotEchoUploadedSecretsOrPaths() {
        when(engine.importOpenApiFile(any())).thenReturn(new EngineApiImportAccess.ImportResult(
                List.of("private-definition token=secret /zap/content-test/definition.json", "parser detail")));
        assertThat(service.importContent(CONTENT, TARGET)).contains("2 ZAP warning/error", "may be partial")
                .doesNotContain("token=secret", "private-definition", "/zap/content-test", "ready to scan");
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void timeoutRetainsDefinitionAndSanitizesOutcomeAndCause() {
        when(engine.importOpenApiFile(any())).thenThrow(new ZapApiException(
                "secret private-definition /zap/content-test", new SocketTimeoutException("private transport detail")));
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outcome is unconfirmed")
                .hasMessageContaining("inspect the ZAP session before retrying")
                .hasMessageNotContaining("secret").hasMessageNotContaining("/zap/content-test").hasNoCause();
        assertThat(stagedDirectories()).hasSize(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "does_not_exist, could not read the staged definition",
            "illegal_parameter, rejected an import parameter",
            "bad_external_data, rejected the staged OpenAPI definition"
    })
    void definiteEngineRejectionCleansStagingWithoutExposingDiagnosticText(String code, String expectedMessage) {
        when(engine.importOpenApiFile(any())).thenThrow(new ZapApiException("private staged content", new org.zaproxy.clientapi.core.ClientApiException(
                "private-definition", code, "/zap/content-test/private-location")));
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZAP").hasMessageContaining(expectedMessage)
                .hasMessageNotContaining("private-definition").hasMessageNotContaining("/zap/content-test").hasNoCause();
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void retainedCountBoundsRepeatedUncertainCalls() {
        properties.setMaxRetainedImports(1);
        when(engine.importOpenApiFile(any())).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("unconfirmed");
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("capacity is full");
        assertThat(stagedDirectories()).hasSize(1);
    }

    @Test
    void uncertainOutcomeGetsFullRetentionPeriodAfterLongRunningImport() {
        when(engine.importOpenApiFile(any())).thenAnswer(invocation -> {
            clock.advanceMinutes(61);
            throw new IllegalStateException("disconnected after slow import");
        });
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("unconfirmed");
        service.reapExpiredFiles();
        assertThat(stagedDirectories()).hasSize(1);
        clock.advanceMinutes(61);
        service.reapExpiredFiles();
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void expiryAndRestartRecoverCrashLeftoversWithoutTreatingRetentionAsCompletion() {
        when(engine.importOpenApiFile(any())).thenThrow(new IllegalStateException("disconnected"));
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("unconfirmed");
        service.close();
        clock.advanceMinutes(61);
        OpenApiContentImportService restarted = createService();
        restarted.initialize();
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void secondWriterCannotUseOrReapSameRoot() {
        service.initialize();
        OpenApiContentImportService second = createService();
        assertThatThrownBy(second::initialize).hasMessageContaining("one MCP writer");
        verifyNoInteractions(engine);
    }

    @Test
    void disabledOrUnconfiguredTopologyRejectsBeforeContentDispatch() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("disabled");
        properties.setEnabled(true);
        properties.setZapDirectory("");
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).hasMessageContaining("shared directory");
        verifyNoInteractions(engine);
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void unsafePermissionsAndReportOverlapFailClosed() throws IOException {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
        assertThatThrownBy(service::initialize).hasMessageContaining("shared directory");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        ReflectionTestUtils.setField(service, "reportDirectory", root.toString());
        assertThatThrownBy(service::initialize).hasMessageContaining("shared directory");
        verifyNoInteractions(engine);
    }

    @Test
    void symlinkRootAndAncestorAreRejectedWithoutTouchingOtherFiles() throws IOException {
        Path link = temp.toRealPath().resolve("linked");
        Files.createSymbolicLink(link, root);
        properties.setLocalDirectory(link.toString());
        assertThatThrownBy(service::initialize).hasMessageContaining("no symlinks");
        properties.setLocalDirectory(link.resolve("child").toString());
        assertThatThrownBy(service::initialize).hasMessageContaining("no symlinks");
        verifyNoInteractions(engine);
    }

    @Test
    void reaperDoesNotFollowChildSymlinkOrDeleteUnexpectedFiles() throws IOException {
        service.initialize();
        Path victim = temp.toRealPath().resolve("victim");
        Files.createDirectory(victim);
        Files.writeString(victim.resolve("definition.json"), "keep me");
        Files.createSymbolicLink(root.resolve("import-" + "a".repeat(64) + "-11111111-1111-1111-1111-111111111111"), victim);
        clock.advanceMinutes(61);
        service.reapExpiredFiles();
        assertThat(Files.readString(victim.resolve("definition.json"))).isEqualTo("keep me");
    }

    @Test
    void malformedAndBlockedContentNeverMaterializesOrDispatches() {
        assertThatThrownBy(() -> service.importContent("not an API definition", TARGET)).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("blocked target")).when(policy).validateUrl(TARGET);
        assertThatThrownBy(() -> service.importContent(CONTENT, TARGET)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stagedDirectories()).isEmpty();
        verifyNoInteractions(engine);
    }

    @Test
    void concurrentWorkspacesUseIsolatedFilesAndReaperSkipsActiveCalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        java.util.Set<String> paths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        when(workspace.resolveCurrentWorkspaceId()).thenAnswer(invocation -> Thread.currentThread().getName());
        when(engine.importOpenApiFile(any())).thenAnswer(invocation -> {
            EngineApiImportAccess.FileImportRequest request = invocation.getArgument(0);
            paths.add(request.filePath());
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.exists(localFile(request.filePath()))).isTrue();
            return new EngineApiImportAccess.ImportResult(List.of());
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.importContent(CONTENT, TARGET));
            var second = executor.submit(() -> service.importContent(CONTENT, TARGET));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(stagedDirectories()).hasSize(2);
                assertThat(paths.size()).isEqualTo(2);
                clock.advanceMinutes(61);
                service.reapExpiredFiles();
                assertThat(stagedDirectories()).hasSize(2);
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).contains("completed");
            assertThat(second.get(10, TimeUnit.SECONDS)).contains("completed");
        }
        assertThat(stagedDirectories()).isEmpty();
    }

    @Test
    void oldUnmanagedDirectoryIsNotDeleted() throws IOException {
        service.initialize();
        Path unrelated = root.resolve("operator-files");
        Files.createDirectory(unrelated);
        Files.setLastModifiedTime(unrelated, FileTime.from(clock.instant().minusSeconds(7200)));
        service.reapExpiredFiles();
        assertThat(unrelated).exists();
    }

    @Test
    void fifthConcurrentImportIsRejectedBeforeParsingOrEngineDispatch() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(engine.importOpenApiFile(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new EngineApiImportAccess.ImportResult(List.of());
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                results.add(executor.submit(() -> service.importContent(CONTENT, TARGET)));
            }
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.importContent("invalid fifth request", TARGET))
                        .hasMessageContaining("capacity is busy");
                assertThat(stagedDirectories()).hasSize(4);
            } finally {
                release.countDown();
            }
            for (var result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).contains("completed");
            }
        }
        assertThat(stagedDirectories()).isEmpty();
    }

    private OpenApiContentImportService createService() {
        var created = new OpenApiContentImportService(engine, policy, properties, workspace, clock);
        services.add(created);
        return created;
    }

    private Path localFile(String zapPath) {
        return root.resolve(Path.of("/zap/content-test").relativize(Path.of(zapPath)));
    }

    private List<Path> stagedDirectories() {
        try (var entries = Files.list(root)) {
            return entries.filter(p -> p.getFileName().toString().startsWith("import-")).toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-10-04T00:00:00Z");
        void advanceMinutes(int minutes) { instant = instant.plusSeconds(minutes * 60L); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
