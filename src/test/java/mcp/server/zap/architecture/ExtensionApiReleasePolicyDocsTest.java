package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExtensionApiReleasePolicyDocsTest {

    private static final Path RAW_POLICY = Path.of("docs/extensions/EXTENSION_API_RELEASE_POLICY.md");
    private static final Path STARLIGHT_POLICY =
            Path.of("docs/src/content/docs/extensions/extension-api-release-policy.md");
    private static final Path STARLIGHT_CONFIG = Path.of("docs/astro.config.mjs");
    private static final Path STARLIGHT_INDEX = Path.of("docs/src/content/docs/index.mdx");
    private static final String RAW_POLICY_TITLE = "Extension API Release Policy";
    private static final String RAW_POLICY_HEADING = "# " + RAW_POLICY_TITLE + "\n\n";

    @Test
    void publishedStarlightPolicyMatchesCanonicalRawPolicy() throws IOException {
        String rawPolicy = Files.readString(RAW_POLICY);
        String starlightPolicy = Files.readString(STARLIGHT_POLICY);
        String starlightBody = stripFrontmatter(starlightPolicy);

        assertThat(rawPolicy).startsWith(RAW_POLICY_HEADING);
        assertThat(starlightPolicy).contains("title: \"" + RAW_POLICY_TITLE + "\"");
        assertThat(starlightBody)
                .as("Starlight policy page must mirror the canonical raw release policy body below the H1")
                .isEqualTo(rawPolicy.substring(RAW_POLICY_HEADING.length()));
    }

    @Test
    void publishedDocsSurfaceExtensionApiReleasePolicy() throws IOException {
        assertThat(Files.readString(STARLIGHT_CONFIG))
                .contains("label: 'Extensions'")
                .contains("slug: 'extensions/extension-api-release-policy'");
        assertThat(Files.readString(STARLIGHT_INDEX))
                .contains("./extensions/extension-api-release-policy/")
                .contains("EXTENSION_POLICY");
    }

    private String stripFrontmatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return markdown;
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalStateException("Starlight policy frontmatter is not closed");
        }
        return markdown.substring(end + "\n---\n".length());
    }
}
