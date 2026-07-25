package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GitHubCiPackArchitectureTest {

    private static final Path ACTION = Path.of(".github/actions/zap-security-gate/action.yml");
    private static final Path ACTION_COMPOSE = Path.of(".github/actions/zap-security-gate/docker-compose.ci.yml");
    private static final Path ACTION_RUN_GATE = Path.of(".github/actions/zap-security-gate/run-gate.sh");
    private static final Path EXAMPLE_WORKFLOW = Path.of("examples/github-actions/zap-security-gate.yml");
    private static final Path EXAMPLE_SEED_REQUESTS = Path.of("examples/github-actions/seed-requests.json");
    private static final Path EXAMPLE_APP_COMPOSE = Path.of("examples/github-actions/docker-compose.app-under-test.yml");
    private static final Path EXAMPLE_GITLAB_WORKFLOW = Path.of("examples/gitlab/zap-security-gate.gitlab-ci.yml");
    private static final Path EXAMPLE_GITLAB_RUN_GATE = Path.of("examples/gitlab/run-zap-security-gate.sh");
    private static final Path JUICE_SHOP_WORKFLOW = Path.of(".github/workflows/zap-security-gate-juice-shop.yml");

    @Test
    void exampleWorkflowUsesThePilotAppOverrideByDefault() throws IOException {
        String workflow = Files.readString(EXAMPLE_WORKFLOW);

        assertThat(workflow)
                .contains("default: http://app:80")
                .contains("baseline_mode:")
                .contains("default: seed")
                .contains("run-active-scan: \"false\"")
                .contains("seed-requests-file: examples/github-actions/seed-requests.json")
                .contains("baseline-mode: ${{ inputs.baseline_mode }}")
                .contains("fail-on-new-findings: ${{ inputs.fail_on_new_findings }}")
                .contains("compose-override-file: examples/github-actions/docker-compose.app-under-test.yml")
                .contains("compose-services: app zap mcp-server")
                .doesNotContain("compose-override-file: .github/zap/docker-compose.app-under-test.yml");
    }

    @Test
    void exampleAppComposeIsMinimalAndReachableFromZapNetwork() throws IOException {
        String compose = Files.readString(EXAMPLE_APP_COMPOSE);

        assertThat(compose)
                .contains("services:")
                .contains("app:")
                .contains("image: nginx:1.27-alpine")
                .contains("expose:")
                .contains("- \"80\"")
                .doesNotContain("ports:");
    }

    @Test
    void gitLabExampleDefinesRequiredImageAndExplicitSeedMode() throws IOException {
        assertThat(Files.readString(EXAMPLE_GITLAB_WORKFLOW))
                .contains("MCP_SERVER_IMAGE: ghcr.io/dtkmn/mcp-zap-server:<release-tag>")
                .contains("ZAP_BASELINE_MODE: seed")
                .contains("ZAP_SEED_REQUESTS_FILE: examples/github-actions/seed-requests.json")
                .contains("ZAP_FAIL_ON_NEW_FINDINGS: \"false\"");
    }

    @Test
    void exampleSeedRequestShowsApiPostShapeWithoutSecrets() throws IOException {
        String seedRequests = Files.readString(EXAMPLE_SEED_REQUESTS);

        assertThat(seedRequests)
                .contains("\"requests\"")
                .contains("\"method\": \"POST\"")
                .contains("\"url\": \"http://app:80/api/example\"")
                .contains("\"expectedStatus\"")
                .doesNotContain("Authorization")
                .doesNotContain("Cookie");
    }

    @Test
    void validationWorkflowWatchesAllGithubActionExamples() throws IOException {
        String workflow = Files.readString(JUICE_SHOP_WORKFLOW);

        assertThat(workflow)
                .contains("\"examples/github-actions/**\"")
                .contains(".github/actions/zap-security-gate");
    }

    @Test
    void actionStackStillForcesExpertSurfaceForCiDiffContracts() throws IOException {
        assertThat(Files.readString(ACTION))
                .contains("description: Run an MCP-backed ZAP scan flow in GitHub Actions")
                .contains("metadata-path");
        assertThat(Files.readString(ACTION_COMPOSE))
                .contains("MCP_SERVER_TOOLS_SURFACE: expert")
                .contains("${LOCAL_ZAP_WORKSPACE_FOLDER}:/zap/wrk");
    }

    @Test
    void actionMapsWorkspaceScopedReportsBackToArtifacts() throws IOException {
        assertThat(Files.readString(ACTION_RUN_GATE))
                .contains("\"--report-root-container\" \"/zap/wrk\"")
                .contains("\"--report-root-local\" \"${local_workspace_folder}\"")
                .doesNotContain("\"--report-root-container\" \"/zap/wrk/reports\"")
                .doesNotContain("\"--report-root-local\" \"${local_workspace_folder}/reports\"");
    }

    @Test
    void gitLabHelperMapsWorkspaceScopedReportsBackToArtifacts() throws IOException {
        assertThat(Files.readString(EXAMPLE_GITLAB_RUN_GATE))
                .contains("\"--report-root-container\" \"/zap/wrk\"")
                .contains("\"--report-root-local\" \"${local_workspace_folder}\"")
                .doesNotContain("\"--report-root-container\" \"/zap/wrk/reports\"")
                .doesNotContain("\"--report-root-local\" \"${local_workspace_folder}/reports\"");
    }
}
