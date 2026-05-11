package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GitHubCiPackArchitectureTest {

    private static final Path ACTION = Path.of(".github/actions/zap-security-gate/action.yml");
    private static final Path ACTION_COMPOSE = Path.of(".github/actions/zap-security-gate/docker-compose.ci.yml");
    private static final Path EXAMPLE_WORKFLOW = Path.of("examples/github-actions/zap-security-gate.yml");
    private static final Path EXAMPLE_APP_COMPOSE = Path.of("examples/github-actions/docker-compose.app-under-test.yml");
    private static final Path INTEGRATION_DOC = Path.of("docs/integrations/GITHUB_ACTIONS_INTEGRATION.md");
    private static final Path GITLAB_INTEGRATION_DOC = Path.of("docs/integrations/GITLAB_CI_INTEGRATION.md");
    private static final Path EXAMPLE_GITLAB_WORKFLOW = Path.of("examples/gitlab/zap-security-gate.gitlab-ci.yml");
    private static final Path PILOT_RUNBOOK = Path.of("docs/operator/runbooks/GITHUB_CI_PACK_PILOT_INSTALL.md");
    private static final Path VERIFY_SCRIPT = Path.of("bin/github-ci-pack-verify.sh");
    private static final Path JUICE_SHOP_WORKFLOW = Path.of(".github/workflows/zap-security-gate-juice-shop.yml");

    @Test
    void exampleWorkflowUsesThePilotAppOverrideByDefault() throws IOException {
        String workflow = Files.readString(EXAMPLE_WORKFLOW);

        assertThat(workflow)
                .contains("default: http://app:80")
                .contains("baseline_mode:")
                .contains("default: seed")
                .contains("run-active-scan: \"false\"")
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
    void docsLinkThePilotInstallPathAndExampleOverride() throws IOException {
        assertThat(Files.readString(INTEGRATION_DOC))
                .contains("examples/github-actions/docker-compose.app-under-test.yml")
                .contains("target-url: http://app:80")
                .contains("Replace `<release-tag>` with a real release tag or digest")
                .contains("The action rejects")
                .contains("baseline-mode: seed")
                .contains("baseline-mode: enforce")
                .contains("Baseline behavior is mode-specific")
                .contains("a missing baseline fails the run")
                .doesNotContain("does not fail the run by default")
                .contains(
                        """
                            target-url: http://app:80
                            mcp-server-image: ghcr.io/dtkmn/mcp-zap-server:<release-tag>
                            baseline-mode: seed
                            run-active-scan: "false"
                            fail-on-new-findings: "false"
                            compose-override-file: examples/github-actions/docker-compose.app-under-test.yml
                        """)
                .contains("compose-services: app zap mcp-server")
                .contains("GitHub CI Pack Pilot Install Runbook");

        assertThat(Files.readString(PILOT_RUNBOOK))
                .contains("30-Minute Pilot Checklist")
                .contains("examples/github-actions/zap-security-gate.yml")
                .contains("Replace the local action reference with the release action ref")
                .contains("examples/github-actions/docker-compose.app-under-test.yml")
                .contains("Pin `mcp-server-image` to a release tag or digest")
                .contains("The action rejects the literal `<release-tag>` placeholder")
                .contains("Keep `baseline-mode: seed` until the first baseline is reviewed")
                .contains("Keep `fail-on-new-findings: \"false\"` until the first baseline is reviewed");
    }

    @Test
    void gitLabDocsMirrorImageReferenceRejectionContract() throws IOException {
        assertThat(Files.readString(GITLAB_INTEGRATION_DOC))
                .contains("Bare refs")
                .contains("mutable tags")
                .contains("placeholder refs such as `<release-tag>` are rejected before Docker starts")
                .contains("ZAP_BASELINE_MODE")
                .contains("no findings diff can be produced");
    }

    @Test
    void gitLabExampleDefinesRequiredImageAndExplicitSeedMode() throws IOException {
        assertThat(Files.readString(EXAMPLE_GITLAB_WORKFLOW))
                .contains("MCP_SERVER_IMAGE: ghcr.io/dtkmn/mcp-zap-server:<release-tag>")
                .contains("ZAP_BASELINE_MODE: seed")
                .contains("ZAP_FAIL_ON_NEW_FINDINGS: \"false\"");
    }

    @Test
    void verifierCoversExampleComposeAndSpringResolution() throws IOException {
        String verifier = Files.readString(VERIFY_SCRIPT);

        assertThat(verifier)
                .contains("docker-compose.app-under-test.yml")
                .contains("github-ci-compose-with-example-app.yaml")
                .contains("org.springframework.ai:spring-ai-starter-mcp-server-webflux:2.0.0-M5")
                .contains("org.springframework.boot:spring-boot-starter:4.1.0-RC1 -> 4.0.6");
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
}
