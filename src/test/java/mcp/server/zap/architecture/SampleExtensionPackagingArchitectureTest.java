package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SampleExtensionPackagingArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path SAMPLE_ROOT = Path.of("examples/extensions/policy-metadata-extension");
    private static final Path SAMPLE_SOURCE_ROOT = SAMPLE_ROOT.resolve("src/main/java");
    private static final Path SAMPLE_CONFIGURATION = SAMPLE_SOURCE_ROOT.resolve(
            "mcp/server/zap/examples/extensions/policy/SamplePolicyMetadataExtensionConfiguration.java"
    );
    private static final Path SAMPLE_METADATA = SAMPLE_ROOT.resolve(
            "src/main/resources/META-INF/mcp-zap/extensions/sample-policy-metadata.properties"
    );
    private static final Path SAMPLE_AUTO_CONFIGURATION_IMPORTS = SAMPLE_ROOT.resolve(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    );
    private static final Path SAMPLE_README = SAMPLE_ROOT.resolve("README.md");

    @Test
    void sampleExtensionJarContainsOnlySampleImplementationAndMetadata() throws IOException {
        Path extensionJar = findSampleExtensionJar();

        try (JarFile jar = new JarFile(extensionJar.toFile())) {
            List<String> entries = jar.stream()
                    .map(entry -> entry.getName())
                    .toList();

            assertThat(entries)
                    .contains(
                            "META-INF/mcp-zap/extensions/sample-policy-metadata.properties",
                            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                            "mcp/server/zap/examples/extensions/policy/SamplePolicyMetadataExtensionConfiguration.class"
                    );
            assertThat(entries)
                    .noneMatch(entry -> entry.startsWith("mcp/server/zap/core/"))
                    .noneMatch(entry -> entry.startsWith("mcp/server/zap/extension/api/"))
                    .noneMatch(entry -> entry.startsWith("mcp/server/zap/enterprise/"))
                    .noneMatch(entry -> entry.equals("mcp/server/zap/McpServerApplication.class"));
        }
    }

    @Test
    void sampleExtensionPackagingIsDeclaredSeparatelyFromCoreBuild() throws IOException {
        String buildFile = Files.readString(BUILD_GRADLE);
        String metadata = Files.readString(SAMPLE_METADATA);

        assertThat(buildFile)
                .contains("sampleExtension")
                .contains("extensionApiJar")
                .contains("samplePolicyMetadataExtensionJar")
                .contains("archiveClassifier = 'sample-policy-metadata-extension'")
                .contains("'MCP-ZAP-Extension-Depends-On': 'mcp-zap-extension-api'")
                .doesNotContain("ASG-Extension-Depends-On': 'mcp-zap-extension-api'")
                .doesNotContain("sourceSets.sampleExtension.compileClasspath += sourceSets.main.output")
                .doesNotContain("sampleExtension.compileClasspath += sourceSets.main.output");

        assertThat(metadata)
                .contains("id=sample-policy-metadata")
                .contains("dependsOn=mcp-zap-extension-api")
                .contains("loadsVia=spring-auto-configuration")
                .contains("private=false");

        assertThat(Files.readString(SAMPLE_AUTO_CONFIGURATION_IMPORTS))
                .contains("SamplePolicyMetadataExtensionConfiguration");
    }

    @Test
    void sampleExtensionDependsOnExtensionApiContractsOnly() throws IOException {
        String source = Files.readString(SAMPLE_CONFIGURATION);

        assertThat(source)
                .contains("import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary")
                .contains("PolicyBundleAccessBoundary")
                .contains("@ConditionalOnMissingBean(PolicyBundleAccessBoundary.class)")
                .contains("@AutoConfiguration")
                .doesNotContain("mcp.server.zap.core")
                .doesNotContain("PolicyDryRunService")
                .doesNotContain("org.zaproxy.clientapi.core")
                .doesNotContain("mcp.server.zap.enterprise");
    }

    @Test
    void sampleExtensionDoesNotDeclarePublicMcpTools() throws IOException {
        List<String> violations;
        try (Stream<Path> paths = Files.walk(SAMPLE_SOURCE_ROOT)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> publicToolDeclarationMatches(path).stream())
                    .toList();
        }

        assertThat(violations)
                .as("Sample extensions should prove provider wiring, not add surprise public MCP tools")
                .isEmpty();
    }

    @Test
    void sampleReadmeDoesNotClaimRuntimeMultiEngineSupport() throws IOException {
        assertThat(Files.readString(SAMPLE_README))
                .contains("does not add a scanner engine")
                .contains("does not prove")
                .doesNotContain("Nuclei")
                .doesNotContain("Semgrep")
                .doesNotContain("Burp");
    }

    private Path findSampleExtensionJar() throws IOException {
        Path jar = Path.of("build/libs/" + projectName() + "-" + projectVersion()
                + "-sample-policy-metadata-extension.jar");
        assertThat(jar)
                .as("current samplePolicyMetadataExtensionJar output should exist")
                .isRegularFile();
        return jar;
    }

    private String projectName() throws IOException {
        return Files.readAllLines(Path.of("settings.gradle")).stream()
                .filter(line -> line.startsWith("rootProject.name = '"))
                .map(line -> line.substring("rootProject.name = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project name from settings.gradle"));
    }

    private String projectVersion() throws IOException {
        return Files.readAllLines(BUILD_GRADLE).stream()
                .filter(line -> line.startsWith("version = '"))
                .map(line -> line.substring("version = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project version from build.gradle"));
    }

    private List<String> publicToolDeclarationMatches(Path path) {
        String content = readFile(path);
        return List.of(
                        "import org.springframework.ai.tool.annotation.Tool",
                        "@Tool("
                ).stream()
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
}
