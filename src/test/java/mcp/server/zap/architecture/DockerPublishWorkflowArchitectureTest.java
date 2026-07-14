package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DockerPublishWorkflowArchitectureTest {

    private static final Path CI_WORKFLOW = Path.of(".github/workflows/ci.yml");

    @Test
    void continuousImagePublicationIsLimitedToMainPushes() throws IOException {
        String workflow = Files.readString(CI_WORKFLOW);

        assertThat(workflow)
                .contains("name: Publish main images")
                .contains("if: github.event_name == 'push' && github.ref == 'refs/heads/main'")
                .contains("BRANCH_TAG: main")
                .contains("sha-${{ github.sha }}")
                .doesNotContain("github.ref == 'refs/heads/dev'")
                .doesNotContain("git rev-parse --short");
    }
}
