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
    private static final String ENTERPRISE_PACKAGE = "mcp.server.zap.enterprise";

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

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read source file: " + path, e);
        }
    }
}
