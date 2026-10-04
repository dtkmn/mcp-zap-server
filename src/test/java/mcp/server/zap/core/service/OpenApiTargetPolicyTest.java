package mcp.server.zap.core.service;

import java.util.List;
import java.util.stream.Stream;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.ImportResult;
import mcp.server.zap.core.gateway.EngineApiImportAccess.UrlImportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenApiTargetPolicyTest {
    private static final String SOURCE = "https://8.8.8.8/openapi.yaml";
    private static final String FILE = "/zap/wrk/openapi.yaml";
    private EngineApiImportAccess engine;
    private UrlValidationService policy;
    private OpenApiService service;

    @BeforeEach
    void setup() {
        engine = mock(EngineApiImportAccess.class);
        policy = new UrlValidationService();
        ReflectionTestUtils.setField(policy, "whitelist", List.of());
        ReflectionTestUtils.setField(policy, "blacklist", List.of());
        service = new OpenApiService(engine, policy, mock(OpenApiContentImportService.class));
        when(engine.importOpenApiUrl(any())).thenReturn(new ImportResult(List.of()));
        when(engine.importOpenApiFile(any())).thenReturn(new ImportResult(List.of()));
    }

    static Stream<Arguments> blockedTargets() {
        return Stream.of(
                "http://127.0.0.1/api", "https://[::1]/api", "http://10.1.2.3/api",
                "http://169.254.169.254/api", "http://0.0.0.0/api", "http://224.0.0.1/api",
                "127.0.0.1:8080/api", "//127.0.0.1/api", "[::1]:8080/api",
                "10.1.2.3/api", "169.254.169.254/api")
                .flatMap(target -> Stream.of(Arguments.of(false, target), Arguments.of(true, target)));
    }

    @ParameterizedTest
    @MethodSource("blockedTargets")
    void prohibitedOverrideNeverReachesEngine(boolean file, String target) {
        assertThrows(IllegalArgumentException.class, () -> importSpec(file, target));
        verifyNoInteractions(engine);
    }

    static Stream<Arguments> malformedTargets() {
        return Stream.of("https://", "/dev/v3/", "https:///dev/v3/", "ftp://8.8.8.8/api",
                        "http:8.8.8.8", "https://user@8.8.8.8/api", "8.8.8.8/api?key=value",
                        "https://8.8.8.8/api#fragment", "https://8.8.8.8:65536/api",
                        "https://8.8.8.8:bad/api", "https://8.8.8.8\\@127.0.0.1/api",
                        "https://%38.8.8.8/api", "://8.8.8.8/api", "8.8.8.8/api//v1")
                .flatMap(target -> Stream.of(Arguments.of(false, target), Arguments.of(true, target)));
    }

    @ParameterizedTest
    @MethodSource("malformedTargets")
    void ambiguousOrUnresolvedOverrideNeverReachesEngine(boolean file, String target) {
        assertThrows(IllegalArgumentException.class, () -> importSpec(file, target));
        verifyNoInteractions(engine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://8.8.8.8/api", "http://8.8.8.8:8080", "8.8.8.8:9090/api/",
            "//8.8.8.8/api/", "8.8.8.8", "HTTPS://8.8.8.8/api"})
    void allowedFullAndAuthorityOverridesPreserveEngineResolution(String target) {
        assertTrue(importSpec(false, " " + target + " ").contains("OpenAPI import completed"));
        assertTrue(importSpec(true, " " + target + " ").contains("OpenAPI import completed"));
        verify(engine).importOpenApiUrl(new UrlImportRequest(SOURCE, target));
        verify(engine).importOpenApiFile(new FileImportRequest(FILE, target));
    }

    @Test
    void omittedOverridePreservesDefinitionDerivedTargets() {
        importSpec(false, null);
        importSpec(true, " ");
        verify(engine).importOpenApiUrl(new UrlImportRequest(SOURCE, null));
        verify(engine).importOpenApiFile(new FileImportRequest(FILE, null));
    }

    @Test
    void explicitAllowlistStillAppliesToTargetDistinctFromDefinitionSource() {
        ReflectionTestUtils.setField(policy, "whitelist", List.of("8.8.8.8"));
        assertThrows(IllegalArgumentException.class, () -> importSpec(false, "https://1.1.1.1/api"));
        assertThrows(IllegalArgumentException.class, () -> importSpec(true, "1.1.1.1/api"));
        verifyNoInteractions(engine);
    }

    @Test
    void operatorPrivateNetworkOptInPreservesAuthorizedImport() {
        ReflectionTestUtils.setField(policy, "allowPrivateNetworks", true);
        importSpec(false, "http://10.1.2.3/api");
        importSpec(true, "10.1.2.3/api");
        verify(engine).importOpenApiUrl(new UrlImportRequest(SOURCE, "http://10.1.2.3/api"));
        verify(engine).importOpenApiFile(new FileImportRequest(FILE, "10.1.2.3/api"));
    }

    @Test
    void disabledDestinationPolicyStillRequiresUnambiguousOverrideSyntax() {
        ReflectionTestUtils.setField(policy, "validationEnabled", false);
        assertThrows(IllegalArgumentException.class, () -> importSpec(false, "/dev/v3/"));
        assertThrows(IllegalArgumentException.class, () -> importSpec(true, "https://"));
        verifyNoInteractions(engine);
        importSpec(true, "http://127.0.0.1/api");
        verify(engine).importOpenApiFile(new FileImportRequest(FILE, "http://127.0.0.1/api"));
    }

    private String importSpec(boolean file, String target) {
        return file ? service.importOpenApiSpecFile(FILE, target) : service.importOpenApiSpec(SOURCE, target);
    }
}
