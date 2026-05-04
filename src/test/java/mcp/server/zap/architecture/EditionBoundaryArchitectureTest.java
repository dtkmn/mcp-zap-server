package mcp.server.zap.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EditionBoundaryArchitectureTest {

    private static final Path CORE_ROOT = Path.of("src/main/java/mcp/server/zap/core");
    private static final Path CORE_TEST_ROOT = Path.of("src/test/java/mcp/server/zap/core");
    private static final String ENTERPRISE_PACKAGE = "mcp.server.zap.enterprise";
    private static final List<String> CORE_FORBIDDEN_SNIPPETS = List.of(
            "mcp.server.zap.enterprise",
            "mcp.server.enterprise.",
            "MCP_ENTERPRISE_",
            "EnterprisePolicy",
            "enterprise policy",
            "enterprise hook",
            "enterprise logic"
    );

    @Test
    void coreSourceMustNotReferenceEnterprisePackage() throws IOException {
        assertThat(Files.exists(CORE_ROOT))
                .as("Core source root should exist")
                .isTrue();

        try (Stream<Path> paths = Files.walk(CORE_ROOT)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> readFile(path).contains(ENTERPRISE_PACKAGE))
                    .map(Path::toString)
                    .toList();

            assertThat(violations)
                    .as("Core source files must not reference enterprise package")
                    .isEmpty();
        }
    }

    @Test
    void coreSourceMustStayEditionNeutral() throws IOException {
        assertNoForbiddenSnippets(CORE_ROOT, "Core source files must stay portable to OSS");
    }

    @Test
    void coreTestsMustNotOwnEnterpriseScenarios() throws IOException {
        assertNoForbiddenSnippets(CORE_TEST_ROOT, "Core test files must not own enterprise scenarios");
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read source file: " + path, e);
        }
    }

    private void assertNoForbiddenSnippets(Path root, String message) throws IOException {
        assertThat(Files.exists(root))
                .as(root + " should exist")
                .isTrue();

        try (Stream<Path> paths = Files.walk(root)) {
            List<String> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> forbiddenSnippetMatches(path).stream())
                    .toList();

            assertThat(violations)
                    .as(message)
                    .isEmpty();
        }
    }

    private List<String> forbiddenSnippetMatches(Path path) {
        String content = readFile(path);
        return CORE_FORBIDDEN_SNIPPETS.stream()
                .filter(content::contains)
                .map(snippet -> path + " contains '" + snippet + "'")
                .toList();
    }
}
