package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.logging.CorrelationIds;
import mcp.gateway.core.policy.ToolPolicyDeniedException;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.gateway.core.url.UrlScope;
import org.junit.jupiter.api.Test;

class ZapSecurityPackCoreConsumptionArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path SETTINGS_GRADLE = Path.of("settings.gradle");
    private static final Path GRADLE_PROPERTIES = Path.of("gradle.properties");
    private static final Path CORE_CONSUMPTION_GRADLE =
            Path.of("gradle/security-pack-core-consumption.gradle");
    private static final Path EXTENSION_MODEL_DOC = Path.of("docs/extensions/README.md");

    @Test
    void runtimeConsumesPublishedGatewayCoreArtifact() throws Exception {
        String expectedVersion = gatewayCoreVersion();
        List<Path> codeSources = List.of(
                        ToolAuthorizationDecision.class,
                        ToolPolicyDeniedException.class,
                        McpAbuseProtectionDecision.class,
                        TokenBucketRateLimiter.class,
                        UrlScope.class,
                        CorrelationIds.class
                ).stream()
                .map(this::codeSourcePath)
                .distinct()
                .toList();

        assertThat(codeSources)
                .as("core contracts should resolve from one external artifact")
                .hasSize(1);

        Path coreArtifact = codeSources.get(0);
        assertThat(coreArtifact.getFileName().toString())
                .isEqualTo("mcp-gateway-core-" + expectedVersion + ".jar");
        assertThat(coreArtifact.toString())
                .doesNotContain("/build/classes/")
                .doesNotContain("/src/main/java/");
    }

    @Test
    void gradleWiringKeepsGatewayCoreExternalAndVerified() throws IOException {
        String buildFile = Files.readString(BUILD_GRADLE);
        String settingsFile = Files.readString(SETTINGS_GRADLE);
        String propertiesFile = Files.readString(GRADLE_PROPERTIES);
        String consumptionGate = Files.readString(CORE_CONSUMPTION_GRADLE);

        assertThat(propertiesFile).contains("gatewayCoreVersion=0.5.4");
        assertThat(buildFile)
                .contains("apply from: file('gradle/security-pack-core-consumption.gradle')")
                .contains("implementation gatewayCoreRuntimeDependency")
                .contains("dependsOn tasks.named('verifyZapSecurityPackCoreConsumption')")
                .contains("dependsOn tasks.named('verifyExternalGatewayCoreResolutionFailsClosed')")
                .doesNotContain("project(':gateway-core')")
                .doesNotContain("gatewayCoreUseLocalProject");
        assertThat(settingsFile)
                .doesNotContain("gateway-core");
        assertThat(consumptionGate)
                .contains("io.github.dtkmn")
                .contains("mcp-gateway-core")
                .contains("verifyZapSecurityPackCoreConsumption")
                .contains("verifyZapSecurityPackCoreConsumptionNegativeTests")
                .contains("verifyExternalGatewayCoreResolutionFailsClosed")
                .contains("BOOT-INF/lib/${gatewayCoreJar.name}")
                .contains("BOOT-INF/classes/mcp/gateway/core/")
                .contains("mcp/gateway/core/")
                .contains("gateway-core source must live in the public mcp-gateway-core repository")
                .doesNotContain("project(':gateway-core')")
                .doesNotContain("gatewayCoreUseLocalProject");
    }

    @Test
    void repositoryDoesNotCarryLocalGatewayCoreSourceOrDuplicatePrimitives() {
        assertThat(Path.of("gateway-core")).doesNotExist();
        assertThat(Path.of("src/main/java/mcp/server/zap/core/service/authz/ToolAuthorizationDecision.java"))
                .doesNotExist();
        assertThat(Path.of("src/main/java/mcp/server/zap/core/exception/ToolPolicyDeniedException.java"))
                .doesNotExist();
        assertThat(Path.of("src/main/java/mcp/server/zap/core/service/protection/McpAbuseProtectionDecision.java"))
                .doesNotExist();
        assertThat(Path.of("src/main/java/mcp/server/zap/core/service/UrlScope.java"))
                .doesNotExist();
    }

    @Test
    void zapRuntimeUsesCoreContractsAtTheAdapterBoundary() throws IOException {
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/authz/ToolAuthorizationService.java")))
                .contains("import mcp.gateway.core.authz.ToolAuthorizationDecision;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/configuration/McpToolAuthorizationWebFilter.java")))
                .contains("import mcp.gateway.core.authz.ToolAuthorizationDecision;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/policy/ToolExecutionPolicyService.java")))
                .contains("import mcp.gateway.core.policy.ToolPolicyDeniedException;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/exception/GlobalExceptionHandler.java")))
                .contains("import mcp.gateway.core.policy.ToolPolicyDeniedException;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/protection/McpAbuseProtectionService.java")))
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionDecision;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/configuration/McpAbuseProtectionWebFilter.java")))
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionDecision;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/observability/ObservabilityService.java")))
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionDecision;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/protection/ClientRateLimiter.java")))
                .contains("import mcp.gateway.core.rate.TokenBucketRateLimiter;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/protection/AuthEndpointRateLimiter.java")))
                .contains("import mcp.gateway.core.rate.TokenBucketRateLimiter;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/logging/RequestLogContext.java")))
                .contains("import mcp.gateway.core.logging.CorrelationIds;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/CoreService.java")))
                .contains("import mcp.gateway.core.url.UrlScope;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/FindingsService.java")))
                .contains("import mcp.gateway.core.url.UrlScope;");
    }

    @Test
    void extensionDocsKeepThePublicCoreClaimModest() throws IOException {
        assertThat(Files.readString(EXTENSION_MODEL_DOC))
                .contains("It consumes")
                .contains("public-preview core contracts from")
                .contains("`io.github.dtkmn:mcp-gateway-core`")
                .contains("it is not the standalone generic MCP")
                .contains("gateway runtime")
                .contains("public-preview core contracts where extracted")
                .doesNotContain("contains both the future-extractable gateway core")
                .doesNotContain("gateway core plus security pack");
    }

    private String gatewayCoreVersion() throws IOException {
        return Files.readAllLines(GRADLE_PROPERTIES).stream()
                .filter(line -> line.startsWith("gatewayCoreVersion="))
                .map(line -> line.substring("gatewayCoreVersion=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("gatewayCoreVersion is missing from gradle.properties"));
    }

    private Path codeSourcePath(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to resolve code source for " + type.getName(), e);
        }
    }
}
