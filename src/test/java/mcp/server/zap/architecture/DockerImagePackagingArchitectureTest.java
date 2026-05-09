package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockerImagePackagingArchitectureTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");

    @Test
    void dockerImageCopiesOnlyExecutableApplicationJar() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile)
                .contains("gradle bootJar -x test")
                .contains("Expected exactly one executable application JAR")
                .contains("cp \"${boot_jars}\" /tmp/app.jar")
                .contains("COPY --from=builder /tmp/app.jar ./app.jar")
                .contains("! -name '*-plain.jar'")
                .contains("! -name '*-enterprise-extension.jar'")
                .contains("! -name '*-sample-policy-metadata-extension.jar'")
                .contains("! -name 'mcp-zap-extension-api-*.jar'")
                .doesNotContain("COPY --from=builder /usr/src/app/build/libs/*.jar ./app.jar")
                .doesNotContain("COPY --from=builder /usr/src/app/build/libs/mcp-zap-server-*.jar ./app.jar");
    }
}
