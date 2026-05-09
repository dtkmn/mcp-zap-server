package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StandaloneExtensionExampleArchitectureTest {

    private static final Path GITIGNORE = Path.of(".gitignore");
    private static final Path ROOT_BUILD = Path.of("build.gradle");
    private static final Path SAMPLE_ROOT = Path.of("examples/extensions/standalone-policy-metadata-extension");
    private static final Path SAMPLE_BUILD = SAMPLE_ROOT.resolve("build.gradle");
    private static final Path SAMPLE_SOURCE_ROOT = SAMPLE_ROOT.resolve("src/main/java");
    private static final Path SAMPLE_METADATA = SAMPLE_ROOT.resolve(
            "src/main/resources/META-INF/mcp-zap/extensions/example-policy-metadata.properties"
    );
    private static final Path SAMPLE_AUTO_CONFIGURATION_IMPORTS = SAMPLE_ROOT.resolve(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
    );

    @Test
    void standaloneSampleDependsOnExtensionApiArtifactOnly() throws IOException {
        String rootBuild = Files.readString(ROOT_BUILD);
        String build = Files.readString(SAMPLE_BUILD);
        String metadata = Files.readString(SAMPLE_METADATA);
        String autoConfigurationImports = Files.readString(SAMPLE_AUTO_CONFIGURATION_IMPORTS);

        assertThat(rootBuild)
                .contains("tasks.register('standalonePolicyMetadataExtensionJar', GradleBuild)")
                .contains("dependsOn project.tasks.named('verifyExtensionApiPublication')")
                .contains("dir = file('examples/extensions/standalone-policy-metadata-extension')")
                .contains("tasks = ['clean', 'jar']")
                .contains("extensionApiRepositoryUrl")
                .contains("dependsOn tasks.named('standalonePolicyMetadataExtensionJar')")
                .contains("finalizedBy tasks.named('cleanStandalonePolicyMetadataExtensionOutput')");

        assertThat(build)
                .contains("id 'java-library'")
                .contains("rootBuildFile = file('../../../build.gradle')")
                .contains("defaultExtensionApiVersion = matcher.group(1)")
                .contains("providers.gradleProperty('extensionApiVersion').orElse(defaultExtensionApiVersion)")
                .contains("defaultExtensionApiRepositoryUrl = file('../../../build/extension-api-publication')")
                .contains("providers.gradleProperty('extensionApiRepositoryUrl')")
                .contains("extensionApiCoordinate = \"mcp.server.zap:mcp-zap-extension-api:${extensionApiVersion}\"")
                .contains("exclusiveContent")
                .contains("includeGroup 'mcp.server.zap'")
                .doesNotContain("mavenLocal()")
                .doesNotContain("mcp-zap-extension-api:" + staleApiVersion())
                .doesNotContain("mcp-zap-server-core")
                .doesNotContain("sourceSets.main.output");

        assertThat(metadata)
                .contains("dependsOn=mcp-zap-extension-api")
                .contains("loadsVia=spring-auto-configuration")
                .contains("private=false");

        assertThat(autoConfigurationImports)
                .contains("com.example.mcpzap.extension.ExamplePolicyMetadataAutoConfiguration");
    }

    @Test
    void standaloneSampleDoesNotImportCoreEnterpriseOrEngineNativeTypes() throws IOException {
        List<String> violations;
        try (Stream<Path> paths = Files.walk(SAMPLE_SOURCE_ROOT)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenImportMatches(path).stream())
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void nestedStandaloneSampleBuildOutputIsIgnored() throws IOException {
        assertThat(Files.readString(GITIGNORE))
                .contains("**/build/")
                .contains("**/.gradle/");
    }

    private List<String> forbiddenImportMatches(Path path) {
        String content = readFile(path);
        return List.of(
                        "import mcp.server.zap.core.",
                        "import mcp.server.zap.enterprise.",
                        "import org.zaproxy.clientapi.",
                        "import org.springframework.ai.tool.annotation.Tool"
                ).stream()
                .filter(content::contains)
                .map(snippet -> path + " contains '" + snippet + "'")
                .toList();
    }

    private String staleApiVersion() throws IOException {
        String rootVersion = Files.readAllLines(Path.of("build.gradle")).stream()
                .filter(line -> line.startsWith("version = '"))
                .map(line -> line.substring("version = '".length(), line.lastIndexOf("'")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not resolve project version from build.gradle"));
        return rootVersion.equals("0.7.0") ? "0.6.0" : "0.7.0";
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
