package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ExtensionApiArtifactArchitectureTest {

    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path EXTENSION_API_SOURCE_ROOT = Path.of("src/main/java/mcp/server/zap/extension/api");

    @Test
    void extensionApiJarContainsOnlyPublicApiContracts() throws IOException {
        Path apiJar = findExtensionApiJar();

        try (JarFile jar = new JarFile(apiJar.toFile())) {
            List<String> entries = jar.stream()
                    .map(entry -> entry.getName())
                    .toList();

            assertThat(entries)
                    .contains(
                            "mcp/server/zap/extension/api/policy/PolicyBundleAccessBoundary.class",
                            "mcp/server/zap/extension/api/policy/ToolExecutionPolicyHook.class",
                            "mcp/server/zap/extension/api/protection/ReportArtifactBoundary.class",
                            "mcp/server/zap/extension/api/evidence/EvidenceMetadataEnricher.class",
                            "mcp/server/zap/extension/api/metadata/ExtensionDescriptor.class"
                    );
            assertThat(entries)
                    .noneMatch(entry -> entry.startsWith("mcp/server/zap/core/"))
                    .noneMatch(entry -> entry.startsWith("mcp/server/zap/enterprise/"))
                    .noneMatch(entry -> entry.startsWith("org/zaproxy/"))
                    .noneMatch(entry -> entry.equals("mcp/server/zap/McpServerApplication.class"));
        }
    }

    @Test
    void extensionApiSourceDoesNotImportRuntimeInternalsOrEngineNativeTypes() throws IOException {
        List<String> violations;
        try (Stream<Path> paths = Files.walk(EXTENSION_API_SOURCE_ROOT)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenImportMatches(path).stream())
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void extensionApiPublicationIsDeclaredAsStandaloneArtifact() throws IOException {
        String buildFile = Files.readString(BUILD_GRADLE);

        assertThat(buildFile)
                .contains("id 'maven-publish'")
                .contains("tasks.register('extensionApiJar', Jar)")
                .contains("archiveBaseName = 'mcp-zap-extension-api'")
                .contains("artifactId = 'mcp-zap-extension-api'")
                .contains("extensionApi(MavenPublication)")
                .contains("'MCP-ZAP-Extension-Api-Version': project.version")
                .doesNotContain("ASG-Extension-Api")
                .contains("root.dependencyManagement?.each { root.remove(it) }")
                .contains("root.dependencies?.each { root.remove(it) }");
    }

    private Path findExtensionApiJar() throws IOException {
        Path jar = Path.of("build/libs/mcp-zap-extension-api-" + projectVersion() + ".jar");
        assertThat(jar)
                .as("current extensionApiJar output should exist")
                .isRegularFile();
        return jar;
    }

    private String projectVersion() throws IOException {
        return Files.readAllLines(BUILD_GRADLE).stream()
                .filter(line -> line.startsWith("version = '"))
                .map(line -> line.substring("version = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project version from build.gradle"));
    }

    private List<String> forbiddenImportMatches(Path path) {
        String content = readFile(path);
        return List.of(
                        "import mcp.server.zap.core.",
                        "import mcp.server.zap.enterprise.",
                        "import org.zaproxy.clientapi.",
                        "import org.springframework."
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
