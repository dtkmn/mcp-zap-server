package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class McpServerVersionArchitectureTest {
    private static final Path BUILD_GRADLE = Path.of("build.gradle");
    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");
    private static final Pattern GRADLE_VERSION = Pattern.compile("^version\\s*=\\s*'([^']+)'\\s*$");
    private static final Pattern MCP_SERVER_VERSION =
            Pattern.compile("^\\s*version:\\s*\\$\\{MCP_SERVER_VERSION:([^}]+)}\\s*$");

    @Test
    void mcpServerMetadataVersionDefaultsToProjectVersionAndAllowsDeploymentOverride() throws IOException {
        String projectVersion = readFirstMatch(BUILD_GRADLE, GRADLE_VERSION);
        String configuredDefault = readFirstMatch(APPLICATION_YML, MCP_SERVER_VERSION);

        assertThat(configuredDefault)
                .as("spring.ai.mcp.server.version default should match Gradle project version")
                .isEqualTo(projectVersion);
    }

    private static String readFirstMatch(Path path, Pattern pattern) throws IOException {
        return Files.readAllLines(path).stream()
                .map(pattern::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No match for " + pattern + " in " + path));
    }
}
