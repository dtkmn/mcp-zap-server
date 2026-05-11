package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SeededApiGatePlaybookDocsTest {

    private static final Path RAW_PLAYBOOK = Path.of("docs/scanning/SEEDED_API_GATE_PLAYBOOK.md");
    private static final Path STARLIGHT_PLAYBOOK =
            Path.of("docs/src/content/docs/scanning/seeded-api-gate-playbook.md");
    private static final Path STARLIGHT_CONFIG = Path.of("docs/astro.config.mjs");
    private static final Path STARLIGHT_INDEX = Path.of("docs/src/content/docs/index.mdx");
    private static final Path GITHUB_INTEGRATION = Path.of("docs/integrations/GITHUB_ACTIONS_INTEGRATION.md");
    private static final Path PILOT_RUNBOOK =
            Path.of("docs/operator/runbooks/GITHUB_CI_PACK_PILOT_INSTALL.md");
    private static final Path ROOT_README = Path.of("README.md");
    private static final String RAW_TITLE = "Seeded API Gate Playbook";
    private static final String RAW_HEADING = "# " + RAW_TITLE + "\n\n";

    @Test
    void publishedStarlightPlaybookMatchesCanonicalRawPlaybook() throws IOException {
        String rawPlaybook = Files.readString(RAW_PLAYBOOK);
        String starlightPlaybook = Files.readString(STARLIGHT_PLAYBOOK);
        String starlightBody = stripFrontmatter(starlightPlaybook);

        assertThat(rawPlaybook).startsWith(RAW_HEADING);
        assertThat(starlightPlaybook).contains("title: \"" + RAW_TITLE + "\"");
        assertThat(starlightBody)
                .as("Starlight playbook must mirror the canonical raw playbook body below the H1")
                .isEqualTo(rawPlaybook.substring(RAW_HEADING.length()));
    }

    @Test
    void docsSurfaceTheSeededApiGatePlaybook() throws IOException {
        assertThat(Files.readString(STARLIGHT_CONFIG))
                .contains("slug: 'scanning/seeded-api-gate-playbook'");
        assertThat(Files.readString(STARLIGHT_INDEX))
                .contains("./scanning/seeded-api-gate-playbook/")
                .contains("SEEDED_API_GATE");
        assertThat(Files.readString(ROOT_README))
                .contains("Seeded API Gate Playbook")
                .contains("scanning/seeded-api-gate-playbook");
        assertThat(Files.readString(GITHUB_INTEGRATION))
                .contains("[Seeded API Gate Playbook](../scanning/SEEDED_API_GATE_PLAYBOOK.md)");
        assertThat(Files.readString(PILOT_RUNBOOK))
                .contains("[Seeded API Gate Playbook](../../scanning/SEEDED_API_GATE_PLAYBOOK.md)");
    }

    @Test
    void playbookTeachesSeedReviewBaselineAndEnforcement() throws IOException {
        String rawPlaybook = Files.readString(RAW_PLAYBOOK);

        assertThat(rawPlaybook)
                .contains("baseline-mode: seed")
                .contains("fail-on-new-findings: \"false\"")
                .contains("seed-requests-results.json")
                .contains("current-findings.json")
                .contains("baseline-mode: enforce")
                .contains("Good Seed Checklist")
                .contains("Artifact Review Checklist")
                .contains("What A Failure Means")
                .contains("no secrets in seed files");
    }

    private String stripFrontmatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return markdown;
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalStateException("Starlight playbook frontmatter is not closed");
        }
        return markdown.substring(end + "\n---\n".length());
    }
}
