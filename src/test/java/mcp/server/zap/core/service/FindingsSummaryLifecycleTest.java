package mcp.server.zap.core.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.configuration.ApiKeyProperties;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.gateway.ZapEngineAdapter;
import mcp.server.zap.core.history.InMemoryScanHistoryStore;
import mcp.server.zap.core.history.ScanHistoryEntry;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.authz.GuidedToolActions;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.RequestIdentityHolder;
import mcp.server.zap.extension.api.evidence.EvidenceMetadataEnricher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FindingsSummaryLifecycleTest {
    private static final String TARGET = "https://target.example/allowed";
    private static final String WORKSPACE = "client-a";

    private final GatewayRecordFactory records = new GatewayRecordFactory();
    private InMemoryScanHistoryStore history;
    private InMemoryScanJobStore jobs;
    private EngineFindingAccess findingAccess;
    private FindingsService findings;
    private GuidedSecurityToolsService guided;
    private ExpertResultsMcpToolsService expert;

    @BeforeEach
    void setUp() {
        RequestIdentityHolder.set(WORKSPACE, WORKSPACE);
        history = new InMemoryScanHistoryStore();
        jobs = new InMemoryScanJobStore();
        StaticListableBeanFactory dependencies = new StaticListableBeanFactory();
        dependencies.addBean("scanJobStore", jobs);
        ScanHistoryLedgerService ledger = new ScanHistoryLedgerService(
                history,
                dependencies.getBeanProvider(ScanJobStore.class),
                new ScanHistoryLedgerProperties(),
                new ClientWorkspaceResolver(new ApiKeyProperties()),
                records,
                dependencies.getBeanProvider(ObjectMapper.class),
                dependencies.getBeanProvider(EvidenceMetadataEnricher.class));
        findingAccess = mock(EngineFindingAccess.class);
        findings = new FindingsService(findingAccess);
        findings.setScanHistoryLedgerService(ledger);
        GuidedToolActions actions = mock(GuidedToolActions.class);
        when(actions.canCall(anyString())).thenReturn(true);
        ReportService reports = mock(ReportService.class);
        guided = new GuidedSecurityToolsService(
                mock(GuidedScanWorkflowService.class), reports, findings,
                mock(OpenApiService.class), new ZapEngineAdapter(), records, actions);
        expert = new ExpertResultsMcpToolsService(findings, reports);
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityHolder.clear();
    }

    @ParameterizedTest
    @EnumSource(ScanJobStatus.class)
    void emptyFindingsStayNeutralAcrossActualQueueStates(ScanJobStatus status) {
        recordJob(status);
        when(findingAccess.loadAlerts(TARGET)).thenReturn(List.of());

        for (EntryPoint entryPoint : EntryPoint.values()) {
            assertNeutralEmptySummary(read(entryPoint, TARGET));
        }
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void emptyFindingsRemainReadableWithoutLifecycleMetadata(EntryPoint entryPoint) {
        history.append(entry("scan_run", null, TARGET, WORKSPACE, Instant.now()));
        when(findingAccess.loadAlerts(TARGET)).thenReturn(List.of());

        assertNeutralEmptySummary(read(entryPoint, TARGET));
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void inProgressFindingsKeepRiskGroupingAndExcludeOtherTargets(EntryPoint entryPoint) {
        recordJob(ScanJobStatus.RUNNING);
        when(findingAccess.loadAlerts(TARGET)).thenReturn(List.of(
                alert("1", TARGET + "/page", "SQL Injection"),
                alert("2", "https://target.example/other", "Excluded sibling"),
                alert("3", "https://other.example/allowed", "Excluded origin")));

        assertThat(read(entryPoint, TARGET))
                .contains("**Total Alerts:** 1", "## 🔴 High Risk", "**SQL Injection** (1 instances)")
                .doesNotContain("Excluded sibling", "Excluded origin", "Scan Complete");
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void findingsForOtherTargetsDoNotTurnAnEmptyScopeIntoCompletion(EntryPoint entryPoint) {
        recordJob(ScanJobStatus.RUNNING);
        when(findingAccess.loadAlerts(TARGET)).thenReturn(List.of(
                alert("1", "https://other.example/allowed", "Excluded origin")));

        assertNeutralEmptySummary(read(entryPoint, TARGET));
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void findingsWithoutEligibleScanEvidenceAreDeniedBeforeEngineRead(EntryPoint entryPoint) {
        history.append(entry("report_artifact", "available", TARGET, WORKSPACE, Instant.now()));

        assertEvidenceDenied(entryPoint, TARGET);
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void anotherWorkspacesScanDoesNotAuthorizeEmptyFindings(EntryPoint entryPoint) {
        history.append(entry("scan_run", "started", TARGET, "client-b", Instant.now()));

        assertEvidenceDenied(entryPoint, TARGET);
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void expiredScanEvidenceDoesNotAuthorizeEmptyFindings(EntryPoint entryPoint) {
        history.append(entry("scan_run", "started", TARGET, WORKSPACE,
                Instant.now().minus(181, ChronoUnit.DAYS)));

        assertEvidenceDenied(entryPoint, TARGET);
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void aVisibleScanDoesNotAuthorizeAnotherTarget(EntryPoint entryPoint) {
        recordJob(ScanJobStatus.RUNNING);

        assertEvidenceDenied(entryPoint, "https://target.example/other");
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void engineReadFailureIsNotReportedAsNoAlerts(EntryPoint entryPoint) {
        recordJob(ScanJobStatus.RUNNING);
        when(findingAccess.loadAlerts(TARGET)).thenThrow(new ZapApiException(
                "Synthetic engine read failure", new IllegalStateException("Synthetic cause")));

        assertThatThrownBy(() -> read(entryPoint, TARGET))
                .isInstanceOf(ZapApiException.class)
                .hasMessage("Synthetic engine read failure");
    }

    private void recordJob(ScanJobStatus status) {
        ScanJob job = new ScanJob("job-1", ScanJobType.SPIDER_SCAN,
                Map.of("targetUrl", TARGET), Instant.now(), 1, WORKSPACE, null);
        if (status != ScanJobStatus.QUEUED) {
            job.markRunning("engine-1");
            switch (status) {
                case SUCCEEDED -> job.markSucceeded(100);
                case FAILED -> job.markFailed("Synthetic failure");
                case CANCELLED -> job.markCancelled();
                default -> { }
            }
        }
        jobs.upsertAll(List.of(job));
    }

    private ScanHistoryEntry entry(String type, String status, String target, String workspace, Instant recordedAt) {
        return new ScanHistoryEntry("entry-1", recordedAt, type, "spider", status, "zap",
                records.targetFromUrl(target, TargetDescriptor.Kind.WEB), "direct", "engine-1",
                null, null, null, null, workspace, workspace, Map.of());
    }

    private AlertSnapshot alert(String id, String url, String name) {
        return new AlertSnapshot(id, "40018", name, "Recorded finding", "High", "Medium", url,
                null, null, null, null, null, null, null, null);
    }

    private String read(EntryPoint entryPoint, String target) {
        return switch (entryPoint) {
            case SERVICE -> findings.getFindingsSummary(target);
            case GUIDED -> guided.getGuidedFindingsSummary(target);
            case EXPERT -> expert.getFindingsSummary(target);
        };
    }

    private void assertNeutralEmptySummary(String summary) {
        assertThat(summary)
                .contains("No alerts are currently recorded for this target.")
                .contains("does not establish scan completion, coverage, or safety")
                .doesNotContain("✅", "Scan Complete", "Completed: yes", "No vulnerabilities");
    }

    private void assertEvidenceDenied(EntryPoint entryPoint, String target) {
        assertThatThrownBy(() -> read(entryPoint, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No visible scan evidence exists");
        verifyNoInteractions(findingAccess);
    }

    private enum EntryPoint {
        SERVICE, GUIDED, EXPERT
    }
}
