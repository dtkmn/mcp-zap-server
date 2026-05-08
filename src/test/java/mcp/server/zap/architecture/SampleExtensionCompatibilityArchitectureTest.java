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
import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SampleExtensionCompatibilityArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final String SAMPLE_AUTO_CONFIGURATION =
            "mcp.server.zap.examples.extensions.policy.SamplePolicyMetadataExtensionConfiguration";

    @Test
    void sampleExtensionJarWiresThroughSpringAutoConfigurationAndExtensionApi() throws Exception {
        Path sampleJar = findSampleExtensionJar();
        Path apiJar = findExtensionApiJar();

        try (URLClassLoader extensionClassLoader = new URLClassLoader(
                new URL[]{sampleJar.toUri().toURL(), apiJar.toUri().toURL()},
                getClass().getClassLoader())) {
            List<String> candidates = ImportCandidates
                    .load(AutoConfiguration.class, extensionClassLoader)
                    .getCandidates();

            assertThat(candidates).contains(SAMPLE_AUTO_CONFIGURATION);

            Class<?> configurationClass = Class.forName(SAMPLE_AUTO_CONFIGURATION, true, extensionClassLoader);
            assertThat(Path.of(configurationClass.getProtectionDomain().getCodeSource().getLocation().toURI()))
                    .isEqualTo(sampleJar.toAbsolutePath().normalize());

            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.setClassLoader(extensionClassLoader);
                TestPropertyValues
                        .of("mcp.server.sample.policy-metadata-extension.enabled=true")
                        .applyTo(context);
                context.register(configurationClass);
                context.refresh();

                PolicyBundleAccessBoundary boundary = context.getBean(PolicyBundleAccessBoundary.class);
                Map<String, Object> summary = new LinkedHashMap<>();
                boundary.enrichBundleSummary(summary, Map.of("tenant", "demo-tenant", "workspace", "demo-workspace"));

                assertThat(summary)
                        .containsKey("sampleExtension");
                assertThat((Map<String, Object>) summary.get("sampleExtension"))
                        .containsEntry("provider", "sample-policy-metadata")
                        .containsEntry("mode", "metadata-only")
                        .containsEntry("enforcesAccess", false);
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
