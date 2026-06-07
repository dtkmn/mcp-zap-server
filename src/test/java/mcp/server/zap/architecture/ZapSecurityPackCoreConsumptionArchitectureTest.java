package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.gateway.core.logging.CorrelationIds;
import mcp.gateway.core.policybundle.PolicyBundleEvaluator;
import mcp.gateway.core.protection.McpAbuseProtectionContext;
import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.gateway.core.tool.McpToolRegistry;
import mcp.gateway.core.url.UrlScope;
import org.junit.jupiter.api.Test;

class ZapSecurityPackCoreConsumptionArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path SETTINGS_GRADLE = Path.of("settings.gradle");
    private static final Path GRADLE_PROPERTIES = Path.of("gradle.properties");
    private static final Path EXTENSION_MODEL_DOC = Path.of("docs/extensions/README.md");
    private static final Path EXTENSION_API_SOURCE_ROOT = Path.of("src/main/java/mcp/server/zap/extension/api");

    @Test
    void runtimeConsumesPublishedGatewayCoreArtifact() throws Exception {
        String expectedVersion = gatewayCoreVersion();
        List<Path> codeSources = List.of(
                        McpToolInvocation.class,
                        McpToolRegistry.class,
                        McpToolAuthorizer.class,
                        GatewayToolExecutionContext.class,
                        PolicyBundleEvaluator.class,
                        GatewayAuditEvent.class,
                        McpAbuseProtectionContext.class,
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
    void gradleWiringUsesNormalMavenCentralDependency() throws IOException {
        String buildFile = Files.readString(BUILD_GRADLE);
        String settingsFile = Files.readString(SETTINGS_GRADLE);
        String propertiesFile = Files.readString(GRADLE_PROPERTIES);

        assertThat(propertiesFile).contains("gatewayCoreVersion=0.5.7");
        assertThat(buildFile)
                .contains("implementation \"io.github.dtkmn:mcp-gateway-core:${gatewayCoreVersion}\"")
                .doesNotContain("apply from: file('gradle/security-pack-core-consumption.gradle')")
                .doesNotContain("verifyZapSecurityPackCoreConsumption")
                .doesNotContain("verifyExternalGatewayCoreResolutionFailsClosed")
                .doesNotContain("gatewayCoreRuntimeDependency")
                .doesNotContain("project(':gateway-core')")
                .doesNotContain("gatewayCoreUseLocalProject");
        assertThat(settingsFile)
                .doesNotContain("gateway-core");
        assertThat(buildFile).doesNotContain("mavenLocal()");
        assertThat(Path.of("gradle/security-pack-core-consumption.gradle"))
                .doesNotExist();
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
                .contains("import mcp.gateway.core.authz.McpToolAuthorizer;")
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("authorizer.authorize(context, grantedScopes, allowWildcard(), !isDisabled())")
                .doesNotContain("missingScopes.add");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/configuration/McpToolAuthorizationWebFilter.java")))
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("import mcp.gateway.core.invocation.McpToolInvocation;")
                .contains("toolAuthorizationService.authorize(grantedScopes, toolContext)");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/policy/ToolExecutionPolicyService.java")))
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("import mcp.gateway.core.policy.ToolPolicyDecision;")
                .contains("import mcp.gateway.core.policy.ToolPolicyDeniedException;")
                .contains("GatewayToolExecutionContext.of(")
                .contains("gatewayCorePolicyAdapter.evaluateExtensionHook(")
                .doesNotContain("new ToolExecutionPolicyContext(");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/exception/GlobalExceptionHandler.java")))
                .contains("import mcp.gateway.core.policy.ToolPolicyDeniedException;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/protection/McpAbuseProtectionService.java")))
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionContext;")
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionDecision;")
                .contains("import mcp.gateway.core.protection.McpQuotaLimit;")
                .contains("toolScopeRegistry.hasCapability(")
                .doesNotContain("QUEUE_ADMISSION_TOOLS");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/configuration/McpAbuseProtectionWebFilter.java")))
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("import mcp.gateway.core.invocation.McpToolInvocation;")
                .contains("protectionService.evaluate(toolContext)");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/observability/ObservabilityService.java")))
                .contains("import mcp.gateway.core.audit.GatewayAuditSink;")
                .contains("import mcp.gateway.core.protection.McpAbuseProtectionDecision;")
                .contains("gatewayCoreAuditAdapter.policyDecision(");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/observability/AuditEventStream.java")))
                .contains("import mcp.gateway.core.audit.GatewayAuditEvent;")
                .contains("import mcp.gateway.core.audit.GatewayAuditSink;")
                .contains("implements GatewayAuditSink");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/gateway/GatewayCorePolicyAdapter.java")))
                .contains("import mcp.gateway.core.context.GatewayToolExecutionContext;")
                .contains("import mcp.gateway.core.policy.ToolPolicyDecision;")
                .contains("import mcp.gateway.core.policy.ToolPolicyEvaluationContext;")
                .contains("import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/gateway/GatewayCoreAuditAdapter.java")))
                .contains("import mcp.gateway.core.audit.GatewayAuditEvent;")
                .contains("import mcp.gateway.core.context.GatewayExecutionContext;");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/authz/ToolScopeRegistry.java")))
                .contains("import mcp.gateway.core.authz.McpToolAccessRegistry;")
                .contains("import mcp.gateway.core.authz.McpToolAuthorizer;")
                .contains("import mcp.gateway.core.tool.McpToolRegistry;")
                .contains("public static final String TOOLS_LIST_ACTION = McpToolAuthorizer.TOOLS_LIST_ACTION;")
                .contains("GUIDED_SCAN_CAPABILITY")
                .contains("QUEUE_ADMISSION_CAPABILITY");
        assertThat(Files.readString(Path.of("src/main/java/mcp/server/zap/core/service/policy/PolicyDryRunService.java")))
                .contains("import mcp.gateway.core.policybundle.PolicyBundleEvaluator;")
                .contains("import mcp.gateway.core.policybundle.PolicyBundleRuleset;")
                .contains("PolicyBundleEvaluator.evaluate(")
                .contains("import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;")
                .doesNotContain("matchesAnyHost(")
                .doesNotContain("matchesWindow(");
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
    void extensionApiDoesNotDependBackOnGatewayCoreOrZapRuntimeInternals() throws IOException {
        assertThat(forbiddenMatches(EXTENSION_API_SOURCE_ROOT, List.of(
                        "mcp.gateway.core",
                        "org.zaproxy",
                        "EngineAdapter",
                        "ScanJob",
                        "ScanHistory",
                        "ClientApi",
                        "org.springframework"
                )))
                .isEmpty();
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

    private List<String> forbiddenMatches(Path root, List<String> forbiddenSnippets) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> matchesIn(path, forbiddenSnippets).stream())
                    .toList();
        }
    }

    private List<String> matchesIn(Path path, List<String> forbiddenSnippets) {
        String content = readFile(path);
        return forbiddenSnippets.stream()
                .filter(content::contains)
                .map(snippet -> path + " contains '" + snippet + "'")
                .toList();
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private Path codeSourcePath(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            return Path.of(location.toURI()).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unable to resolve code source for " + type.getName(), e);
        }
    }
}
