package mcp.server.zap.core.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanHistoryLedgerServiceTest {
    private InMemoryScanJobStore scanJobStore;
    private ScanHistoryLedgerService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        scanJobStore = new InMemoryScanJobStore();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ScanHistoryLedgerProperties properties = new ScanHistoryLedgerProperties();
        properties.setMaxListEntries(10);
        properties.setMaxExportEntries(10);
        properties.setRetentionDays(180);
        service = new ScanHistoryLedgerService(
                new InMemoryScanHistoryStore(),
                scanJobStore,
                properties,
                new ClientWorkspaceResolver(new ApiKeyProperties()),
                new GatewayRecordFactory(),
                objectMapper
        );
    }

    @Test
    void queueJobsAppearAsQueryableHistoryEntries() {
        ScanJob job = new ScanJob(
                "job-1",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "https://app.example.com", "recurse", "true", "policy", "Baseline"),
                Instant.parse("2026-05-04T00:00:00Z"),
                3,
                "client-a",
                "idem-a"
        );
        job.incrementAttempts();
        job.markRunning("zap-active-1");
        job.updateProgress(45);
        scanJobStore.upsertAll(java.util.List.of(job));

        String list = service.listHistory("scan_job", "running", "app.example.com", 5);
        String detail = service.getHistoryEntry("job:job-1");

        assertThat(list)
                .contains("Scan history ledger")
                .contains("job:job-1 [scan_job]")
                .contains("Status: running")
                .contains("Target: https://app.example.com");
        assertThat(detail)
                .contains("Entry ID: job:job-1")
                .contains("Backend Reference: zap-active-1")
                .contains("lastKnownProgress: 45");
    }

    @Test
    void directScansAndReportArtifactsAreRecordedAndExportable() throws Exception {
        service.recordDirectScanStarted(
                "active_scan",
                "active-77",
                "https://api.example.com",
                Map.of("policy", "Baseline")
        );
        service.recordReportArtifact(
                "/zap/wrk/zap-report-77.json",
                "traditional-json-plus",
                "https://api.example.com",
                Map.of("template", "traditional-json-plus")
        );

        String list = service.listHistory(null, null, "api.example.com", 10);
        String export = service.exportHistory(null, null, "api.example.com", 10);
        JsonNode root = objectMapper.readTree(export);

        assertThat(list)
                .contains("[scan_run]")
                .contains("[report_artifact]")
                .contains("active-77")
                .contains("/zap/wrk/zap-report-77.json");
        assertThat(root.path("version").asInt()).isEqualTo(1);
        assertThat(root.path("entryCount").asInt()).isEqualTo(2);
        assertThat(root.path("entries").findValuesAsText("evidenceType"))
                .contains("scan_run", "report_artifact");
    }

    @Test
    void releaseEvidenceBundleSummarizesEntriesAndWarnings() throws Exception {
        ScanJob runningJob = new ScanJob(
                "job-release-running",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "https://api.example.com/queued"),
                Instant.parse("2026-05-06T00:00:00Z"),
                3,
                "client-a",
                "release-idem"
        );
        runningJob.incrementAttempts();
        runningJob.markRunning("zap-active-release");
        scanJobStore.upsertAll(java.util.List.of(runningJob));
        service.recordDirectScanStarted(
                "active_scan",
                "active-release",
                "https://api.example.com",
                Map.of("policy", "Baseline")
        );
        service.recordReportArtifact(
                "/zap/wrk/release-report.json",
                "traditional-json-plus",
                "https://api.example.com",
                Map.of("template", "traditional-json-plus")
        );

        String bundle = service.exportReleaseEvidence("pilot-alpha", "api.example.com", 10);
        JsonNode root = objectMapper.readTree(bundle);

        assertThat(root.path("purpose").asText()).isEqualTo("release_evidence");
        assertThat(root.path("releaseName").asText()).isEqualTo("pilot-alpha");
        assertThat(root.path("summary").path("entryCount").asInt()).isEqualTo(3);
        assertThat(root.path("summary").path("byEvidenceType").path("scan_job").asInt()).isEqualTo(1);
        assertThat(root.path("summary").path("byEvidenceType").path("scan_run").asInt()).isEqualTo(1);
        assertThat(root.path("summary").path("byEvidenceType").path("report_artifact").asInt()).isEqualTo(1);
        assertThat(root.path("summary").path("hasScanEvidence").asBoolean()).isTrue();
        assertThat(root.path("summary").path("hasReportArtifact").asBoolean()).isTrue();
        assertThat(root.path("summary").path("nonTerminalScanJobs").asInt()).isEqualTo(1);
        assertThat(root.path("entries").findValuesAsText("id"))
                .contains("job:job-release-running");
        assertThat(root.path("warnings").toString())
                .contains("not terminal")
                .doesNotContain("No report artifact");
    }

    @Test
    void releaseEvidenceBundleWarnsWhenEvidenceIsIncomplete() throws Exception {
        service.recordDirectScanStarted(
                "spider",
                "spider-only",
                "https://scan-only.example.com",
                Map.of()
        );

        JsonNode scanOnly = objectMapper.readTree(
                service.exportReleaseEvidence(null, "scan-only.example.com", 5)
        );
        JsonNode empty = objectMapper.readTree(
                service.exportReleaseEvidence("empty-release", "missing.example.com", 5)
        );

        assertThat(scanOnly.path("releaseName").asText()).isEqualTo("unnamed-release");
        assertThat(scanOnly.path("summary").path("hasScanEvidence").asBoolean()).isTrue();
        assertThat(scanOnly.path("summary").path("hasReportArtifact").asBoolean()).isFalse();
        assertThat(scanOnly.path("warnings").toString())
                .contains("No report artifact")
                .doesNotContain("No scan evidence");
        assertThat(empty.path("summary").path("entryCount").asInt()).isZero();
        assertThat(empty.path("warnings").toString())
                .contains("No scan history entries matched");
    }

    @Test
    void customerHandoffOmitsInternalLedgerFieldsAndRawMetadata() {
        ScanJob job = new ScanJob(
                "job-customer-safe",
                ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", "https://shop.example.com"),
                Instant.parse("2026-05-06T00:00:00Z"),
                2,
                "client-a",
                "customer-idem-key"
        );
        job.incrementAttempts();
        job.markRunning("zap-spider-9");
        job.markSucceeded(100);
        scanJobStore.upsertAll(java.util.List.of(job));
        service.recordReportArtifact(
                "/zap/wrk/internal-report.html",
                "traditional-html-plus",
                "https://shop.example.com",
                Map.of("template", "traditional-html-plus", "internalTicket", "SEC-123")
        );

        String handoff = service.exportCustomerHandoff("pilot-public", "shop.example.com", 10);

        assertThat(handoff)
                .contains("Customer Evidence Handoff")
                .contains("Handoff: pilot-public")
                .contains("Evidence Window: filtered selection")
                .contains("Readiness: PASS")
                .contains("https://shop.example.com")
                .contains("Queued Scan: Spider Scan")
                .contains("Succeeded, progress 100%")
                .contains("HTML report generated")
                .contains("Attach reviewed report files separately")
                .doesNotContain(
                        "job-customer-safe",
                        "job:",
                        "zap-spider-9",
                        "client-a",
                        "workspace",
                        "backendReference",
                        "artifactLocation",
                        "customer-idem-key",
                        "idempotencyKey",
                        "/zap/wrk",
                        "SEC-123",
                        "metadata"
                );
    }

    @Test
    void customerHandoffDoesNotEchoInternalArtifactPathFilters() {
        service.recordReportArtifact(
                "/zap/wrk/private/internal-report.html",
                "traditional-html-plus",
                "https://path-filter.example.com",
                Map.of("template", "traditional-html-plus")
        );
        service.recordDirectScanStarted(
                "spider",
                "spider-private-filter",
                "https://path-filter.example.com",
                Map.of()
        );

        String handoff = service.exportCustomerHandoff(
                "pilot-path-filter",
                "/zap/wrk/private/internal-report.html",
                10
        );

        assertThat(handoff)
                .contains("Evidence Window: filtered selection")
                .contains("https://path-filter.example.com")
                .contains("HTML report generated")
                .doesNotContain(
                        "/zap/wrk",
                        "private/internal-report.html",
                        "spider-private-filter",
                        "Target Filter"
                );
    }

    @Test
    void customerHandoffFlagsDirectScanStartsAsLaunchEvidenceOnly() {
        service.recordDirectScanStarted(
                "active_scan",
                "active-direct-1",
                "https://direct.example.com",
                Map.of("policy", "Baseline")
        );
        service.recordReportArtifact(
                "/zap/wrk/direct-report.html",
                "traditional-html-plus",
                "https://direct.example.com",
                Map.of("template", "traditional-html-plus")
        );

        String handoff = service.exportCustomerHandoff("pilot-direct", "direct.example.com", 10);

        assertThat(handoff)
                .contains("Readiness: CAVEAT")
                .contains("Direct scan starts prove launch only")
                .contains("Direct Scan Start: Active Scan")
                .doesNotContain("active-direct-1", "/zap/wrk/direct-report.html");
    }

    @Test
    void unknownEntryReturnsBoundedError() {
        assertThatThrownBy(() -> service.getHistoryEntry("hist_missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No scan history entry found for ID: hist_missing");
    }
}
