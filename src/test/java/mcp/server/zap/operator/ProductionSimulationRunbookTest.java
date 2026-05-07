package mcp.server.zap.operator;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSimulationRunbookTest {

    @Test
    void pilotProofScenarioCoversAuthPolicyScanAndEvidenceInOneFlow() throws Exception {
        String runbook = Files.readString(Path.of(
                "docs/src/content/docs/operations/production-simulation-runbook.md"));

        assertThat(runbook).contains(
                "Pilot Proof Scenario: Governed Authenticated Scan",
                "zap_auth_session_prepare",
                "zap_auth_session_validate",
                "zap_crawl_start",
                "zap_attack_start",
                "zap_passive_scan_wait",
                "zap_findings_summary",
                "zap_findings_details",
                "zap_report_generate",
                "zap_scan_history_release_evidence",
                "zap_scan_history_customer_handoff",
                "policy_decision",
                "guided `Operation ID`",
                "report artifact path",
                "Concrete blockers must become follow-up issues"
        );
    }

    @Test
    void pilotProofEvidenceContractNamesTheOperatorArtifacts() throws Exception {
        String runbook = Files.readString(Path.of(
                "docs/src/content/docs/operations/production-simulation-runbook.md"));

        assertThat(runbook).contains(
                "Correlation IDs",
                "Auth evidence",
                "Policy evidence",
                "Scan evidence",
                "Passive evidence",
                "Findings evidence",
                "Report evidence",
                "Release evidence",
                "Customer handoff",
                "Secret handling",
                "guided operation ID and backend scan/job ID"
        );
        assertThat(runbook).doesNotContain("top-secret-token", "StrongPassword123!");
    }

    @Test
    void releaseEvidenceHandoffRunbookDefinesReviewerContract() throws Exception {
        String runbook = Files.readString(Path.of(
                "docs/src/content/docs/operations/release-evidence-handoff-runbook.md"));

        assertThat(runbook).contains(
                "zap_scan_history_release_evidence",
                "zap_scan_history_customer_handoff",
                "Warning Interpretation",
                "No scan evidence entries were included",
                "No report artifact entries were included",
                "Queued scan jobs are not terminal",
                "Evidence entry count reached the export limit",
                "curated summary",
                "Acceptance Checklist",
                "Customer-Safe Redaction Contract",
                "Pass / Caveat / Fail",
                "Sign-Off Template"
        );
        assertThat(runbook).doesNotContain("top-secret-token", "StrongPassword123!");
    }
}
