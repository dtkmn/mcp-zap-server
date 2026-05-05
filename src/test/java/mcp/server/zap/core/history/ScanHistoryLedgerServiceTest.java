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
    void unknownEntryReturnsBoundedError() {
        assertThatThrownBy(() -> service.getHistoryEntry("hist_missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No scan history entry found for ID: hist_missing");
    }
}
