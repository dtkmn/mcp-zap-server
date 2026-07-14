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
                .contains("COPY build.gradle settings.gradle gradle.properties ./")
                .contains("COPY gradle ./gradle")
                .contains("COPY src ./src")
                .contains("gradle bootJar -x test")
                .contains("Expected exactly one executable application JAR")
                .contains("cp \"${boot_jars}\" /tmp/app.jar")
                .contains("addgroup -S -g 1000 app")
                .contains("adduser -S -D -H -u 1000 -G app app")
                .contains("mkdir -p /app /zap/wrk")
                .contains("chown -R 1000:1000 /app /zap/wrk")
                .contains("COPY --chown=1000:1000 --from=builder /tmp/app.jar ./app.jar")
                .contains("USER 1000:1000")
                .contains("! -name '*-plain.jar'")
                .contains("! -name '*-enterprise-extension.jar'")
                .contains("! -name '*-sample-policy-metadata-extension.jar'")
                .contains("! -name 'mcp-zap-extension-api-*.jar'")
                .doesNotContain("COPY --from=builder /usr/src/app/build/libs/*.jar ./app.jar")
                .doesNotContain("COPY --from=builder /usr/src/app/build/libs/mcp-zap-server-*.jar ./app.jar")
                .doesNotContain("USER root");
    }
}
