package mcp.server.zap.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SelfServeFirstRunDocsTest {

    private static final Path README = Path.of("README.md");
    private static final Path RAW_GUIDE = Path.of("docs/getting-started/SELF_SERVE_FIRST_RUN.md");
    private static final Path STARLIGHT_GUIDE =
            Path.of("docs/src/content/docs/getting-started/self-serve-first-run.md");
    private static final Path STARLIGHT_CONFIG = Path.of("docs/astro.config.mjs");
    private static final Path STARLIGHT_INDEX = Path.of("docs/src/content/docs/index.mdx");
    private static final Path CLIENT_AUTH_DOC =
            Path.of("docs/src/content/docs/getting-started/mcp-client-authentication.md");
    private static final Path CURSOR_EXAMPLE = Path.of("examples/cursor/mcp.json");
    private static final Path MARKETPLACE_METADATA = Path.of(".mcp/server.json");
    private static final Path LLMS_INSTALL = Path.of("llms-install.md");
    private static final Path DOCTOR = Path.of("bin/self-serve-doctor.sh");
    private static final Path BOOTSTRAP = Path.of("bin/bootstrap-local.sh");
    private static final Path DEV_COMPOSE = Path.of("docker-compose.dev.yml");

    @Test
    void readmeStartsNewUsersOnTheSupportedSelfServePath() throws IOException {
        String readme = Files.readString(README);

        assertThat(readme)
                .contains("./bin/bootstrap-local.sh")
                .contains("./dev.sh")
                .contains("./bin/self-serve-doctor.sh")
                .contains("examples/cursor/mcp.json")
                .contains("getting-started/self-serve-first-run")
                .contains("http://juice-shop:3000")
                .contains("http://petstore:8080")
                .doesNotContain("Generate values for ZAP_API_KEY and MCP_API_KEY");
    }

    @Test
    void docsSiteSurfacesTheFirstRunGuide() throws IOException {
        assertThat(Files.readString(STARLIGHT_CONFIG))
                .contains("slug: 'getting-started/self-serve-first-run'");
        assertThat(Files.readString(STARLIGHT_INDEX))
                .contains("./getting-started/self-serve-first-run/")
                .contains("SELF_SERVE_FIRST_RUN");
        assertThat(Files.readString(CLIENT_AUTH_DOC))
                .contains("[Self-Serve First Run](./self-serve-first-run/)");

        assertThat(Files.readString(RAW_GUIDE))
                .contains("git clone https://github.com/dtkmn/mcp-zap-server.git")
                .contains("./bin/self-serve-doctor.sh")
                .contains("http://juice-shop:3000")
                .contains("Create release evidence and a customer-safe handoff");

        assertThat(Files.readString(STARLIGHT_GUIDE))
                .contains("title: \"Self-Serve First Run\"")
                .contains("git clone https://github.com/dtkmn/mcp-zap-server.git")
                .contains("./bin/self-serve-doctor.sh")
                .contains("http://juice-shop:3000")
                .contains("Create release evidence and a customer-safe handoff");
    }

    @Test
    void cursorExampleUsesTheGeneratedApiKeyHeader() throws IOException {
        assertThat(Files.readString(CURSOR_EXAMPLE))
                .contains("\"url\": \"http://localhost:7456/mcp\"")
                .contains("\"X-API-Key\": \"${env:MCP_API_KEY}\"")
                .doesNotContain("Authorization")
                .doesNotContain("Bearer");
    }

    @Test
    void doctorVerifiesTheGuidedScanReportAndEvidenceSurface() throws IOException {
        String doctor = Files.readString(DOCTOR);

        assertThat(doctor)
                .contains("guided scan, report, and evidence tool visibility")
                .contains("required_guided_tools=(")
                .contains("zap_crawl_start")
                .contains("zap_passive_scan_wait")
                .contains("zap_findings_summary")
                .contains("zap_report_generate")
                .contains("zap_report_read")
                .contains("zap_scan_history_release_evidence")
                .contains("zap_scan_history_customer_handoff")
                .contains("docs/getting-started/SELF_SERVE_FIRST_RUN.md")
                .contains("docs/src/content/docs/getting-started/self-serve-first-run.md")
                .doesNotContain("--with-open-webui");

        assertThat(Files.readString(BOOTSTRAP)).doesNotContain("--with-open-webui");
    }

    @Test
    void marketplaceGuidanceKeepsReportReadbackOnTheGuidedSurface() throws IOException {
        assertThat(Files.readString(MARKETPLACE_METADATA))
                .contains("including report readback")
                .contains("Use expert only when clients need raw ZAP tools outside the guided surface")
                .doesNotContain("expert when clients need raw ZAP tools such as zap_report_read");

        assertThat(Files.readString(LLMS_INSTALL))
                .contains("Keep `MCP_SERVER_TOOLS_SURFACE=guided` for normal report readback")
                .contains("Use `-e MCP_SERVER_TOOLS_SURFACE=expert` only when you need lower-level ZAP tools outside the guided surface")
                .doesNotContain("If you need expert tools such as `zap_report_read`");
    }

    @Test
    void devComposeDoesNotDisableTheApiKeyFirstRunPath() throws IOException {
        assertThat(Files.readString(DEV_COMPOSE))
                .contains("keep the same runtime behavior as the base stack")
                .doesNotContain("MCP_SECURITY_ENABLED: \"false\"")
                .doesNotContain("MCP_SECURITY_MODE=none");
    }
}
