package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.core.service.policy.PolicyDryRunService;
import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SampleExtensionCompatibilityArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path STANDALONE_SAMPLE_LIBS =
            Path.of("examples/extensions/standalone-policy-metadata-extension/build/libs");
    private static final String SAMPLE_AUTO_CONFIGURATION =
            "mcp.server.zap.examples.extensions.policy.SamplePolicyMetadataExtensionConfiguration";
    private static final String STANDALONE_SAMPLE_AUTO_CONFIGURATION =
            "com.example.mcpzap.extension.ExamplePolicyMetadataAutoConfiguration";

    @Test
    void sampleExtensionJarWiresThroughSpringAutoConfigurationAndExtensionApi() throws Exception {
        Path sampleJar = findSampleExtensionJar();
        Path apiJar = findExtensionApiJar();

        Map<String, Object> summary = enrichBundleSummaryThroughAutoConfiguration(
                sampleJar,
                apiJar,
                SAMPLE_AUTO_CONFIGURATION,
                "mcp.server.sample.policy-metadata-extension.enabled=true"
        );

        assertThat(summary)
                .containsKey("sampleExtension");
        assertThat(extensionMetadata(summary, "sampleExtension"))
                .containsEntry("provider", "sample-policy-metadata")
                .containsEntry("mode", "metadata-only")
                .containsEntry("enforcesAccess", false);
    }

    @Test
    void standaloneExtensionJarWiresIntoGatewayPolicyDryRunService() throws Exception {
        Path standaloneJar = findStandaloneSampleExtensionJar();
        Path apiJar = findExtensionApiJar();

        assertThat(standaloneJar.getFileName().toString())
                .startsWith("standalone-policy-metadata-extension-");

        Map<String, Object> response = previewPolicyThroughGatewayContext(
                standaloneJar,
                apiJar,
                STANDALONE_SAMPLE_AUTO_CONFIGURATION,
                "example.mcp-zap.policy-metadata-extension.enabled=true"
        );

        assertThat(validation(response)).containsEntry("valid", true);
        assertThat(decision(response)).containsEntry("result", "allow");
        assertThat(bundle(response))
                .containsKey("exampleExtension");
        assertThat(extensionMetadata(bundle(response), "exampleExtension"))
                .containsEntry("provider", "example-standalone-policy-metadata")
                .containsEntry("mode", "metadata-only")
                .containsEntry("enforcesAccess", false)
                .containsEntry("labelCount", 2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extensionMetadata(Map<String, Object> summary, String key) {
        assertThat(summary.get(key)).isInstanceOf(Map.class);
        return (Map<String, Object>) summary.get(key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validation(Map<String, Object> response) {
        return (Map<String, Object>) response.get("validation");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decision(Map<String, Object> response) {
        return (Map<String, Object>) response.get("decision");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bundle(Map<String, Object> response) {
        return (Map<String, Object>) response.get("bundle");
    }

    private Map<String, Object> previewPolicyThroughGatewayContext(
            Path extensionJar,
            Path apiJar,
            String autoConfiguration,
            String enabledProperty) throws Exception {
        try (URLClassLoader extensionClassLoader = new URLClassLoader(
                new URL[]{extensionJar.toUri().toURL(), apiJar.toUri().toURL()},
                getClass().getClassLoader())) {
            List<String> candidates = ImportCandidates
                    .load(AutoConfiguration.class, extensionClassLoader)
                    .getCandidates();

            assertThat(candidates).contains(autoConfiguration);

            Class<?> configurationClass = Class.forName(autoConfiguration, true, extensionClassLoader);
            assertThat(Path.of(configurationClass.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .isEqualTo(extensionJar.toAbsolutePath().normalize());

            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.setClassLoader(extensionClassLoader);
                TestPropertyValues
                        .of(enabledProperty)
                        .applyTo(context);
                context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
                context.registerBean(ToolScopeRegistry.class, (Supplier<ToolScopeRegistry>) ToolScopeRegistry::new);
                context.register(configurationClass, PolicyDryRunService.class);
                context.refresh();

                PolicyDryRunService policyDryRunService = context.getBean(PolicyDryRunService.class);
                return policyDryRunService.preview(
                        standalonePolicyBundle(),
                        "zap_report_read",
                        "https://example.com",
                        "2026-04-06T09:00:00Z"
                );
            }
        }
    }

    private String standalonePolicyBundle() throws IOException {
        return """
                {
                  "apiVersion": "%s",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "standalone-extension-proof",
                    "description": "proves standalone extension wiring",
                    "owner": "platform",
                    "labels": {
                      "tenant": "demo-tenant",
                      "workspace": "demo-workspace"
                    }
                  },
                  "spec": {
                    "defaultDecision": "deny",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      {
                        "id": "allow-report-read",
                        "description": "allow report reads",
                        "decision": "allow",
                        "reason": "test proof",
                        "match": {
                          "tools": ["zap_report_read"],
                          "hosts": ["example.com"]
                        }
                      }
                    ]
                  }
                }
                """.formatted(policyBundleApiVersion());
    }

    private String policyBundleApiVersion() throws IOException {
        return Files.readAllLines(Path.of("src/main/java/mcp/server/zap/core/service/policy/PolicyDryRunService.java")).stream()
                .filter(line -> line.contains("policy/v1") && line.contains(".equals(apiVersion)"))
                .map(line -> line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve policy bundle API version"));
    }

    private Map<String, Object> enrichBundleSummaryThroughAutoConfiguration(
            Path extensionJar,
            Path apiJar,
            String autoConfiguration,
            String enabledProperty) throws Exception {
        try (URLClassLoader extensionClassLoader = new URLClassLoader(
                new URL[]{extensionJar.toUri().toURL(), apiJar.toUri().toURL()},
                getClass().getClassLoader())) {
            List<String> candidates = ImportCandidates
                    .load(AutoConfiguration.class, extensionClassLoader)
                    .getCandidates();

            assertThat(candidates).contains(autoConfiguration);

            Class<?> configurationClass = Class.forName(autoConfiguration, true, extensionClassLoader);
            assertThat(Path.of(configurationClass.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .isEqualTo(extensionJar.toAbsolutePath().normalize());

            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.setClassLoader(extensionClassLoader);
                TestPropertyValues
                        .of(enabledProperty)
                        .applyTo(context);
                context.register(configurationClass);
                context.refresh();

                PolicyBundleAccessBoundary boundary = context.getBean(PolicyBundleAccessBoundary.class);
                Map<String, Object> summary = new LinkedHashMap<>();
                boundary.enrichBundleSummary(summary, Map.of("tenant", "demo-tenant", "workspace", "demo-workspace"));
                return summary;
            }
        }
    }

    private Path findSampleExtensionJar() throws IOException {
        Path jar = Path.of("build/libs/" + projectName() + "-" + projectVersion()
                + "-sample-policy-metadata-extension.jar");
        assertThat(jar)
                .as("current samplePolicyMetadataExtensionJar output should exist")
                .isRegularFile();
        return jar.toAbsolutePath().normalize();
    }

    private Path findStandaloneSampleExtensionJar() throws IOException {
        assertThat(STANDALONE_SAMPLE_LIBS)
                .as("standalone sample extension build/libs directory should exist")
                .isDirectory();
        try (Stream<Path> paths = Files.list(STANDALONE_SAMPLE_LIBS)) {
            List<Path> jars = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("standalone-policy-metadata-extension-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            assertThat(jars)
                    .as("exactly one standalone sample extension JAR should be built")
                    .hasSize(1);
            return jars.getFirst().toAbsolutePath().normalize();
        }
    }

    private String projectName() throws IOException {
        return Files.readAllLines(Path.of("settings.gradle")).stream()
                .filter(line -> line.startsWith("rootProject.name = '"))
                .map(line -> line.substring("rootProject.name = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project name from settings.gradle"));
    }

    private Path findExtensionApiJar() throws IOException {
        Path jar = Path.of("build/libs/mcp-zap-extension-api-" + projectVersion() + ".jar");
        assertThat(jar)
                .as("current extensionApiJar output should exist")
                .isRegularFile();
        return jar.toAbsolutePath().normalize();
    }

    private String projectVersion() throws IOException {
        return Files.readAllLines(BUILD_GRADLE).stream()
                .filter(line -> line.startsWith("version = '"))
                .map(line -> line.substring("version = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project version from build.gradle"));
    }
}
