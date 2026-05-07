package mcp.server.zap.core.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.gateway.ArtifactRecord;
import mcp.server.zap.core.gateway.GatewayRecordFactory;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.ScanHistoryAccessBoundary;
import mcp.server.zap.core.service.protection.ScanJobAccessBoundary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Queryable evidence ledger for scan jobs, direct scan starts, and report artifacts.
 */
@Service
public class ScanHistoryLedgerService {
    private static final String ENGINE_ZAP = "zap";
    private static final String TYPE_SCAN_JOB = "scan_job";
    private static final String TYPE_SCAN_RUN = "scan_run";
    private static final String TYPE_REPORT_ARTIFACT = "report_artifact";
    private static final Comparator<ScanHistoryEntry> ENTRY_ORDER = Comparator
            .comparing(ScanHistoryEntry::recordedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ScanHistoryEntry::id, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed();

    private final ScanHistoryStore historyStore;
    private final ScanJobStore scanJobStore;
    private final ScanHistoryLedgerProperties properties;
    private final ClientWorkspaceResolver clientWorkspaceResolver;
    private final GatewayRecordFactory gatewayRecordFactory;
    private final ObjectMapper objectMapper;
    private ScanJobAccessBoundary scanJobAccessBoundary;
    private ScanHistoryAccessBoundary scanHistoryAccessBoundary;

    @Autowired
    public ScanHistoryLedgerService(ScanHistoryStore historyStore,
                                    ObjectProvider<ScanJobStore> scanJobStoreProvider,
                                    ScanHistoryLedgerProperties properties,
                                    ClientWorkspaceResolver clientWorkspaceResolver,
                                    GatewayRecordFactory gatewayRecordFactory,
                                    ObjectProvider<ObjectMapper> objectMapperProvider) {
        this(
                historyStore,
                scanJobStoreProvider.getIfAvailable(InMemoryScanJobStore::new),
                properties,
                clientWorkspaceResolver,
                gatewayRecordFactory,
                objectMapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    ScanHistoryLedgerService(ScanHistoryStore historyStore,
                             ScanJobStore scanJobStore,
                             ScanHistoryLedgerProperties properties,
                             ClientWorkspaceResolver clientWorkspaceResolver,
                             GatewayRecordFactory gatewayRecordFactory,
                             ObjectMapper objectMapper) {
        this.historyStore = historyStore;
        this.scanJobStore = scanJobStore == null ? new InMemoryScanJobStore() : scanJobStore;
        this.properties = properties;
        this.clientWorkspaceResolver = clientWorkspaceResolver;
        this.gatewayRecordFactory = gatewayRecordFactory;
        this.objectMapper = (objectMapper == null ? new ObjectMapper() : objectMapper)
                .copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Autowired(required = false)
    void setScanJobAccessBoundary(ScanJobAccessBoundary scanJobAccessBoundary) {
        this.scanJobAccessBoundary = scanJobAccessBoundary;
    }

    @Autowired(required = false)
    void setScanHistoryAccessBoundary(ScanHistoryAccessBoundary scanHistoryAccessBoundary) {
        this.scanHistoryAccessBoundary = scanHistoryAccessBoundary;
    }

    public void recordDirectScanStarted(String operationKind,
                                        String scanId,
                                        String targetUrl,
                                        Map<String, String> metadata) {
        if (!hasText(scanId)) {
            return;
        }
        TargetDescriptor target = gatewayRecordFactory.optionalTarget(targetUrl, TargetDescriptor.Kind.WEB);
        append(new ScanHistoryEntry(
                newLedgerId(),
                Instant.now(),
                TYPE_SCAN_RUN,
                normalize(operationKind, "scan"),
                "started",
                ENGINE_ZAP,
                target,
                "direct",
                scanId.trim(),
                null,
                null,
                null,
                null,
                currentClientId(),
                currentWorkspaceId(),
                metadata
        ));
    }

    public void recordReportArtifact(String artifactLocation,
                                     String reportTemplate,
                                     String sites,
                                     Map<String, String> metadata) {
        if (!hasText(artifactLocation)) {
            return;
        }
        TargetDescriptor target = gatewayRecordFactory.optionalTarget(sites, TargetDescriptor.Kind.WEB);
        String mediaType = mediaTypeFor(reportTemplate, artifactLocation);
        ArtifactRecord artifact = new ArtifactRecord(
                ENGINE_ZAP,
                artifactLocation.trim(),
                "report",
                artifactLocation.trim(),
                mediaType,
                target
        );
        recordReportArtifact(artifact, metadata);
    }

    public void recordReportArtifact(ArtifactRecord artifact, Map<String, String> metadata) {
        if (artifact == null || !hasText(artifact.location())) {
            return;
        }
        append(new ScanHistoryEntry(
                newLedgerId(),
                Instant.now(),
                TYPE_REPORT_ARTIFACT,
                "report",
                "generated",
                normalize(artifact.engineId(), ENGINE_ZAP),
                artifact.target(),
                "artifact",
                artifact.location(),
                artifact.artifactId(),
                normalize(artifact.artifactType(), "report"),
                artifact.location(),
                normalize(artifact.mediaType(), "application/octet-stream"),
                currentClientId(),
                currentWorkspaceId(),
                metadata
        ));
    }

    public String listHistory(String evidenceType, String status, String targetContains, Integer requestedLimit) {
        int limit = boundedLimit(requestedLimit, properties.getMaxListEntries());
        List<ScanHistoryEntry> entries = queryEntries(evidenceType, status, targetContains, limit);

        StringBuilder output = new StringBuilder();
        output.append("Scan history ledger\n")
                .append("Retention: ").append(retentionDays()).append(" days\n")
                .append("Entries Returned: ").append(entries.size()).append('\n');
        if (entries.isEmpty()) {
            output.append("No scan history entries match current filters.");
            return output.toString();
        }

        int index = 1;
        for (ScanHistoryEntry entry : entries) {
            output.append('\n')
                    .append(index++).append(". ")
                    .append(entry.id()).append(" [").append(entry.evidenceType()).append("]\n")
                    .append("   Recorded At: ").append(entry.recordedAt()).append('\n')
                    .append("   Operation: ").append(entry.operationKind()).append('\n')
                    .append("   Status: ").append(entry.status()).append('\n')
                    .append("   Target: ").append(targetLabel(entry.target())).append('\n');
            if (hasText(entry.backendReference())) {
                output.append("   Backend Reference: ").append(entry.backendReference()).append('\n');
            }
            if (hasText(entry.artifactLocation())) {
                output.append("   Artifact: ").append(entry.artifactLocation()).append('\n');
            }
        }
        return output.toString();
    }

    public String getHistoryEntry(String entryId) {
        String normalizedEntryId = requireText(entryId, "entryId");
        ScanHistoryEntry entry = loadEntry(normalizedEntryId);
        if (entry == null) {
            throw new IllegalArgumentException("No scan history entry found for ID: " + normalizedEntryId);
        }
        if (scanHistoryAccessBoundary != null && !scanHistoryAccessBoundary.canCurrentRequesterAccess(entry)) {
            throw new IllegalArgumentException("No scan history entry found for ID: " + normalizedEntryId);
        }

        StringBuilder output = new StringBuilder();
        output.append("Scan history entry\n")
                .append("Entry ID: ").append(entry.id()).append('\n')
                .append("Recorded At: ").append(entry.recordedAt()).append('\n')
                .append("Evidence Type: ").append(entry.evidenceType()).append('\n')
                .append("Operation: ").append(entry.operationKind()).append('\n')
                .append("Status: ").append(entry.status()).append('\n')
                .append("Engine: ").append(entry.engineId()).append('\n')
                .append("Target: ").append(targetLabel(entry.target())).append('\n')
                .append("Execution Mode: ").append(valueOrDefault(entry.executionMode(), "unknown")).append('\n')
                .append("Client: ").append(valueOrDefault(entry.clientId(), "anonymous")).append('\n')
                .append("Workspace: ").append(valueOrDefault(entry.workspaceId(), "default-workspace")).append('\n');
        if (hasText(entry.backendReference())) {
            output.append("Backend Reference: ").append(entry.backendReference()).append('\n');
        }
        if (hasText(entry.artifactLocation())) {
            output.append("Artifact Type: ").append(valueOrDefault(entry.artifactType(), "artifact")).append('\n')
                    .append("Artifact Location: ").append(entry.artifactLocation()).append('\n')
                    .append("Media Type: ").append(valueOrDefault(entry.mediaType(), "unknown")).append('\n');
        }
        if (!entry.metadata().isEmpty()) {
            output.append("Metadata:\n");
            entry.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(item -> output.append("- ").append(item.getKey()).append(": ").append(item.getValue()).append('\n'));
        }
        return output.toString().trim();
    }

    public String exportHistory(String evidenceType, String status, String targetContains, Integer requestedLimit) {
        int limit = boundedLimit(requestedLimit, properties.getMaxExportEntries());
        List<ScanHistoryEntry> entries = queryEntries(evidenceType, status, targetContains, limit);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("version", 1);
        export.put("generatedAt", Instant.now().toString());
        export.put("retentionDays", retentionDays());
        export.put("entryCount", entries.size());
        export.put("entries", entries.stream().map(this::exportEntry).toList());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to export scan history ledger", e);
        }
    }

    public String exportReleaseEvidence(String releaseName, String targetContains, Integer requestedLimit) {
        int limit = boundedLimit(requestedLimit, properties.getMaxExportEntries());
        List<ScanHistoryEntry> entries = queryEntries(null, null, targetContains, limit);

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("version", 1);
        export.put("purpose", "release_evidence");
        export.put("releaseName", valueOrDefault(releaseName, "unnamed-release"));
        export.put("generatedAt", Instant.now().toString());
        export.put("retentionDays", retentionDays());
        export.put("filters", releaseEvidenceFilters(targetContains, limit));
        export.put("summary", releaseEvidenceSummary(entries));
        export.put("warnings", releaseEvidenceWarnings(entries, limit));
        export.put("entries", entries.stream().map(this::exportEntry).toList());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to export release evidence bundle", e);
        }
    }

    public String exportCustomerHandoff(String handoffName, String targetContains, Integer requestedLimit) {
        int limit = boundedLimit(requestedLimit, properties.getMaxExportEntries());
        List<ScanHistoryEntry> entries = queryEntries(null, null, targetContains, limit);
        CustomerEvidenceCoverage coverage = customerEvidenceCoverage(entries);
        List<String> notes = customerHandoffNotes(entries, limit, coverage);
        List<String> targets = releaseEvidenceTargets(entries);

        StringBuilder output = new StringBuilder();
        output.append("Customer Evidence Handoff\n")
                .append("Handoff: ").append(valueOrDefault(handoffName, "unnamed-handoff")).append('\n')
                .append("Generated At: ").append(Instant.now()).append('\n')
                .append("Evidence Window: ").append(customerEvidenceWindowLabel(targetContains)).append('\n')
                .append("Readiness: ").append(customerReadiness(entries, notes, coverage)).append('\n');

        output.append('\n').append("Targets\n");
        if (targets.isEmpty()) {
            output.append("- none matched\n");
        } else {
            targets.forEach(target -> output.append("- ").append(target).append('\n'));
        }

        output.append('\n').append("Coverage Summary\n")
                .append("- Evidence entries reviewed: ").append(entries.size()).append('\n')
                .append("- Scan evidence included: ").append(yesNo(entries.stream().anyMatch(this::isScanEvidence))).append('\n')
                .append("- Report evidence included: ").append(yesNo(entries.stream().anyMatch(this::isReportArtifact))).append('\n')
                .append("- Finished queued scans: ").append(entries.stream().filter(this::isTerminalScanJob).count()).append('\n')
                .append("- Unfinished queued scans: ").append(entries.stream().filter(this::isNonTerminalScanJob).count()).append('\n');

        appendCustomerAcceptanceChecklist(output, entries, limit, coverage);

        output.append('\n').append("Review Notes\n");
        if (notes.isEmpty()) {
            output.append("- none\n");
        } else {
            notes.forEach(note -> output.append("- ").append(note).append('\n'));
        }

        output.append('\n').append("Evidence Included\n");
        if (entries.isEmpty()) {
            output.append("- No evidence matched this handoff window.\n");
        } else {
            entries.forEach(entry -> appendCustomerEvidenceLine(output, entry));
        }

        output.append('\n').append("Customer-Safe Redaction Contract\n")
                .append("- Raw ledger IDs: excluded\n")
                .append("- Backend scan references: excluded\n")
                .append("- Internal client and tenancy IDs: excluded\n")
                .append("- Internal artifact paths: excluded\n")
                .append("- Raw metadata and idempotency keys: excluded\n")
                .append("- Internal filter selector: excluded\n");

        output.append('\n').append("Customer Package Contents\n")
                .append("- Include this summary.\n")
                .append("- Attach reviewed report files separately.\n")
                .append("- Keep raw ledger JSON internal unless it has been explicitly reviewed and redacted.\n");

        output.append('\n').append("Operator Reminder\n")
                .append("- Use the readiness value and checklist as the package gate.\n")
                .append("- Record accepted caveats in the internal release or pilot record.\n");
        return output.toString().trim();
    }

    public boolean hasVisibleScanEvidenceForTarget(String targetUrl) {
        if (!hasText(targetUrl)) {
            return false;
        }
        TargetDescriptor requestedTarget = gatewayRecordFactory.targetFromUrl(targetUrl, TargetDescriptor.Kind.WEB);
        int limit = Math.max(properties.getMaxListEntries(), properties.getMaxExportEntries());
        return queryEntries(null, null, null, limit).stream()
                .filter(this::isScanEvidence)
                .map(ScanHistoryEntry::target)
                .anyMatch(entryTarget -> targetContains(entryTarget, requestedTarget));
    }

    public List<String> visibleScanTargetBaseUrls() {
        int limit = Math.max(properties.getMaxListEntries(), properties.getMaxExportEntries());
        return queryEntries(null, null, null, limit).stream()
                .filter(this::isScanEvidence)
                .map(ScanHistoryEntry::target)
                .map(target -> target == null ? null : target.baseUrl())
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> visibleScanTargetHosts() {
        return visibleScanTargetBaseUrls().stream()
                .map(this::hostLabel)
                .filter(this::hasText)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Map<String, Object> releaseEvidenceFilters(String targetContains, int limit) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("targetContains", normalizeNullable(targetContains));
        filters.put("limit", limit);
        return filters;
    }

    private Map<String, Object> releaseEvidenceSummary(List<ScanHistoryEntry> entries) {
        List<String> targets = releaseEvidenceTargets(entries);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("entryCount", entries.size());
        summary.put("byEvidenceType", countBy(entries, ScanHistoryEntry::evidenceType));
        summary.put("byStatus", countBy(entries, ScanHistoryEntry::status));
        summary.put("targetCount", targets.size());
        summary.put("targets", targets);
        summary.put("firstRecordedAt", recordedAtBound(entries, true));
        summary.put("lastRecordedAt", recordedAtBound(entries, false));
        summary.put("hasScanEvidence", entries.stream().anyMatch(this::isScanEvidence));
        summary.put("hasReportArtifact", entries.stream().anyMatch(this::isReportArtifact));
        summary.put("nonTerminalScanJobs", entries.stream().filter(this::isNonTerminalScanJob).count());
        return summary;
    }

    private Map<String, Integer> countBy(
            List<ScanHistoryEntry> entries,
            Function<ScanHistoryEntry, String> classifier
    ) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (ScanHistoryEntry entry : entries) {
            String key = valueOrDefault(classifier.apply(entry), "unknown");
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private List<String> releaseEvidenceTargets(List<ScanHistoryEntry> entries) {
        TreeSet<String> targets = new TreeSet<>();
        for (ScanHistoryEntry entry : entries) {
            String target = targetLabel(entry.target());
            if (hasText(target)) {
                targets.add(target);
            }
        }
        return List.copyOf(targets);
    }

    private String recordedAtBound(List<ScanHistoryEntry> entries, boolean first) {
        return entries.stream()
                .map(ScanHistoryEntry::recordedAt)
                .filter(Objects::nonNull)
                .reduce((left, right) -> first
                        ? (left.isBefore(right) ? left : right)
                        : (left.isAfter(right) ? left : right))
                .map(Instant::toString)
                .orElse(null);
    }

    private List<String> releaseEvidenceWarnings(List<ScanHistoryEntry> entries, int limit) {
        ArrayList<String> warnings = new ArrayList<>();
        if (entries.isEmpty()) {
            warnings.add("No scan history entries matched the requested evidence window.");
            return warnings;
        }
        if (entries.stream().noneMatch(this::isScanEvidence)) {
            warnings.add("No scan evidence entries were included; run or queue at least one scan before using this for release sign-off.");
        }
        if (entries.stream().noneMatch(this::isReportArtifact)) {
            warnings.add("No report artifact entries were included; generate a report before attaching this to a release or pilot handoff.");
        }
        long nonTerminalScanJobs = entries.stream().filter(this::isNonTerminalScanJob).count();
        if (nonTerminalScanJobs > 0) {
            warnings.add(nonTerminalScanJobs + " queued scan job(s) are not terminal; wait for completion or use a tighter filter before final sign-off.");
        }
        if (entries.size() >= limit) {
            warnings.add("Evidence entry count reached the export limit; increase limit if the reviewer needs the full evidence window.");
        }
        return warnings;
    }

    private boolean isScanEvidence(ScanHistoryEntry entry) {
        return TYPE_SCAN_JOB.equalsIgnoreCase(entry.evidenceType())
                || TYPE_SCAN_RUN.equalsIgnoreCase(entry.evidenceType());
    }

    private boolean isReportArtifact(ScanHistoryEntry entry) {
        return TYPE_REPORT_ARTIFACT.equalsIgnoreCase(entry.evidenceType());
    }

    private boolean isNonTerminalScanJob(ScanHistoryEntry entry) {
        return TYPE_SCAN_JOB.equalsIgnoreCase(entry.evidenceType())
                && !isTerminalStatus(entry.status());
    }

    private boolean isTerminalScanJob(ScanHistoryEntry entry) {
        return TYPE_SCAN_JOB.equalsIgnoreCase(entry.evidenceType())
                && isTerminalStatus(entry.status());
    }

    private boolean isFailedOrCancelledScanJob(ScanHistoryEntry entry) {
        return TYPE_SCAN_JOB.equalsIgnoreCase(entry.evidenceType())
                && ("failed".equalsIgnoreCase(entry.status()) || "cancelled".equalsIgnoreCase(entry.status()));
    }

    private boolean isDirectScanRun(ScanHistoryEntry entry) {
        return TYPE_SCAN_RUN.equalsIgnoreCase(entry.evidenceType());
    }

    private boolean isTerminalStatus(String status) {
        return "succeeded".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status);
    }

    private CustomerEvidenceCoverage customerEvidenceCoverage(List<ScanHistoryEntry> entries) {
        Map<String, TargetEvidenceBuilder> targetCoverage = new TreeMap<>();
        for (ScanHistoryEntry entry : entries) {
            if (!isScanEvidence(entry) && !isReportArtifact(entry)) {
                continue;
            }
            String key = customerTargetKey(entry.target());
            TargetEvidenceBuilder builder = targetCoverage.computeIfAbsent(
                    key,
                    ignored -> new TargetEvidenceBuilder(targetLabel(entry.target()))
            );
            if (isScanEvidence(entry)) {
                builder.hasScanEvidence = true;
            }
            if (isTerminalScanJob(entry)) {
                builder.hasTerminalQueuedScanEvidence = true;
            }
            if (isDirectScanRun(entry)) {
                builder.hasDirectScanEvidence = true;
            }
            if (isReportArtifact(entry)) {
                builder.hasReportArtifact = true;
            }
        }
        List<TargetEvidenceCoverage> targets = targetCoverage.values().stream()
                .map(TargetEvidenceBuilder::build)
                .toList();
        return new CustomerEvidenceCoverage(targets);
    }

    private String customerTargetKey(TargetDescriptor target) {
        if (target == null) {
            return "target:all";
        }
        if (hasText(target.baseUrl())) {
            return "url:" + canonicalTargetUrl(target.baseUrl());
        }
        if (hasText(target.displayName())) {
            return "label:" + target.displayName().trim().toLowerCase(Locale.ROOT);
        }
        return "target:all";
    }

    private String canonicalTargetUrl(String value) {
        String normalizedValue = value.trim();
        try {
            URI uri = URI.create(normalizedValue);
            String scheme = valueOrDefault(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            String host = valueOrDefault(uri.getHost(), "").toLowerCase(Locale.ROOT);
            if (!hasText(scheme) || !hasText(host)) {
                return normalizedValue.toLowerCase(Locale.ROOT);
            }
            int port = normalizedPort(scheme, uri.getPort());
            return scheme + "://" + host + (port >= 0 ? ":" + port : "") + normalizedTargetPath(uri);
        } catch (IllegalArgumentException ignored) {
            return normalizedValue.toLowerCase(Locale.ROOT);
        }
    }

    private int normalizedPort(String scheme, int port) {
        if (port == 80 && "http".equalsIgnoreCase(scheme)) {
            return -1;
        }
        if (port == 443 && "https".equalsIgnoreCase(scheme)) {
            return -1;
        }
        return port;
    }

    private String normalizedTargetPath(URI uri) {
        String path = uri.normalize().getPath();
        if (!hasText(path)) {
            return "/";
        }
        String trimmed = path.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private List<String> customerHandoffNotes(
            List<ScanHistoryEntry> entries,
            int limit,
            CustomerEvidenceCoverage coverage
    ) {
        ArrayList<String> notes = new ArrayList<>();
        if (entries.isEmpty()) {
            notes.add("No scan or report evidence matched the selected handoff window.");
            return notes;
        }
        boolean hasScanEvidence = entries.stream().anyMatch(this::isScanEvidence);
        boolean hasReportArtifact = entries.stream().anyMatch(this::isReportArtifact);
        if (!hasScanEvidence) {
            notes.add("No scan evidence was included; run or queue at least one scan before customer sign-off.");
        }
        if (!hasReportArtifact) {
            notes.add("No report evidence was included; generate and review a report before customer handoff.");
        }
        if (hasScanEvidence && hasReportArtifact && !coverage.hasCompleteScanReportTarget()) {
            notes.add("No target has both scan evidence and report evidence; use a tighter target filter or add the missing evidence before customer sign-off.");
        } else if (coverage.hasIncompleteTargets()) {
            notes.add("Some targets are missing either scan evidence or report evidence: "
                    + String.join(", ", coverage.incompleteTargetLabels()) + ".");
        }
        if (coverage.hasDirectOnlyScanTargets()) {
            notes.add("Some targets only have direct scan launch evidence; terminal queued completion is not proven for: "
                    + String.join(", ", coverage.directOnlyScanTargetLabels()) + ".");
        }
        long unfinishedJobs = entries.stream().filter(this::isNonTerminalScanJob).count();
        if (unfinishedJobs > 0) {
            notes.add(unfinishedJobs + " queued scan job(s) are not finished; wait for completion or document the caveat.");
        }
        long failedOrCancelledJobs = entries.stream().filter(this::isFailedOrCancelledScanJob).count();
        if (failedOrCancelledJobs > 0) {
            notes.add(failedOrCancelledJobs + " queued scan job(s) ended failed or cancelled; do not present this as clean evidence without explanation.");
        }
        if (entries.size() >= limit) {
            notes.add("The handoff reached the export limit; narrow the target filter or increase the limit before final packaging.");
        }
        return notes;
    }

    private String customerEvidenceWindowLabel(String targetContains) {
        return hasText(targetContains) ? "filtered selection" : "all visible evidence";
    }

    private String customerReadiness(
            List<ScanHistoryEntry> entries,
            List<String> notes,
            CustomerEvidenceCoverage coverage
    ) {
        if (entries.isEmpty()
                || entries.stream().noneMatch(this::isScanEvidence)
                || entries.stream().noneMatch(this::isReportArtifact)
                || !coverage.hasCompleteScanReportTarget()
                || entries.stream().anyMatch(this::isFailedOrCancelledScanJob)) {
            return "FAIL";
        }
        return notes.isEmpty() ? "PASS" : "CAVEAT";
    }

    private void appendCustomerAcceptanceChecklist(
            StringBuilder output,
            List<ScanHistoryEntry> entries,
            int limit,
            CustomerEvidenceCoverage coverage
    ) {
        output.append('\n').append("Acceptance Checklist\n");
        appendChecklistLine(output, "Evidence window has entries", !entries.isEmpty());
        appendChecklistLine(output, "Scan evidence is included", entries.stream().anyMatch(this::isScanEvidence));
        appendChecklistLine(output, "Report evidence is included", entries.stream().anyMatch(this::isReportArtifact));
        appendChecklistLine(output, "At least one target has scan and report evidence", coverage.hasCompleteScanReportTarget());
        appendChecklistLine(output, "Terminal queued scan evidence is included", entries.stream().anyMatch(this::isTerminalScanJob));
        appendChecklistLine(output, "No target relies only on direct scan launch evidence", !coverage.hasDirectOnlyScanTargets());
        appendChecklistLine(output, "No unfinished queued scans", entries.stream().noneMatch(this::isNonTerminalScanJob));
        appendChecklistLine(output, "Evidence window stayed within export limit", entries.size() < limit);
    }

    private void appendChecklistLine(StringBuilder output, String label, boolean passed) {
        output.append("- [").append(passed ? 'x' : ' ').append("] ").append(label).append('\n');
    }

    private record CustomerEvidenceCoverage(List<TargetEvidenceCoverage> targets) {
        boolean hasCompleteScanReportTarget() {
            return targets.stream().anyMatch(TargetEvidenceCoverage::hasScanAndReportEvidence);
        }

        boolean hasIncompleteTargets() {
            return targets.stream().anyMatch(target -> !target.hasScanAndReportEvidence());
        }

        List<String> incompleteTargetLabels() {
            return targets.stream()
                    .filter(target -> !target.hasScanAndReportEvidence())
                    .map(TargetEvidenceCoverage::label)
                    .toList();
        }

        boolean hasDirectOnlyScanTargets() {
            return targets.stream().anyMatch(TargetEvidenceCoverage::hasDirectOnlyScanEvidence);
        }

        List<String> directOnlyScanTargetLabels() {
            return targets.stream()
                    .filter(TargetEvidenceCoverage::hasDirectOnlyScanEvidence)
                    .map(TargetEvidenceCoverage::label)
                    .toList();
        }
    }

    private record TargetEvidenceCoverage(
            String label,
            boolean hasScanEvidence,
            boolean hasReportArtifact,
            boolean hasTerminalQueuedScanEvidence,
            boolean hasDirectScanEvidence
    ) {
        boolean hasScanAndReportEvidence() {
            return hasScanEvidence && hasReportArtifact;
        }

        boolean hasDirectOnlyScanEvidence() {
            return hasDirectScanEvidence && !hasTerminalQueuedScanEvidence;
        }
    }

    private static final class TargetEvidenceBuilder {
        private final String label;
        private boolean hasScanEvidence;
        private boolean hasReportArtifact;
        private boolean hasTerminalQueuedScanEvidence;
        private boolean hasDirectScanEvidence;

        private TargetEvidenceBuilder(String label) {
            this.label = label;
        }

        private TargetEvidenceCoverage build() {
            return new TargetEvidenceCoverage(
                    label,
                    hasScanEvidence,
                    hasReportArtifact,
                    hasTerminalQueuedScanEvidence,
                    hasDirectScanEvidence
            );
        }
    }

    private void appendCustomerEvidenceLine(StringBuilder output, ScanHistoryEntry entry) {
        String target = targetLabel(entry.target());
        if (isReportArtifact(entry)) {
            output.append("- Report: ")
                    .append(formatMediaType(entry.mediaType()))
                    .append(" report generated for ").append(target)
                    .append(" at ").append(entry.recordedAt())
                    .append(". Attach the reviewed report file separately.\n");
            return;
        }
        if (TYPE_SCAN_JOB.equalsIgnoreCase(entry.evidenceType())) {
            output.append("- Queued Scan: ")
                    .append(formatOperation(entry.operationKind()))
                    .append(" for ").append(target)
                    .append(" - ").append(formatStatus(entry.status()));
            String progress = entry.metadata().get("lastKnownProgress");
            if (hasText(progress)) {
                output.append(", progress ").append(progress).append('%');
            }
            if ("true".equalsIgnoreCase(entry.metadata().get("authenticated"))) {
                output.append(", authenticated");
            }
            output.append(", recorded ").append(entry.recordedAt()).append('\n');
            return;
        }
        if (isDirectScanRun(entry)) {
            output.append("- Direct Scan Start: ")
                    .append(formatOperation(entry.operationKind()))
                    .append(" for ").append(target)
                    .append(" - launch recorded at ").append(entry.recordedAt())
                    .append("; terminal completion is not proven by this entry.\n");
            return;
        }
        output.append("- Evidence: ")
                .append(formatOperation(entry.operationKind()))
                .append(" for ").append(target)
                .append(" - ").append(formatStatus(entry.status()))
                .append(", recorded ").append(entry.recordedAt()).append('\n');
    }

    private String formatOperation(String value) {
        String normalized = valueOrDefault(value, "scan")
                .replace('-', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder();
        for (String part : normalized.split("_+")) {
            if (!hasText(part)) {
                continue;
            }
            if (!output.isEmpty()) {
                output.append(' ');
            }
            output.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                output.append(part.substring(1));
            }
        }
        return output.isEmpty() ? "Scan" : output.toString();
    }

    private String formatStatus(String value) {
        String normalized = valueOrDefault(value, "unknown").toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private String formatMediaType(String mediaType) {
        String normalized = valueOrDefault(mediaType, "report").toLowerCase(Locale.ROOT);
        if (normalized.contains("json")) {
            return "JSON";
        }
        if (normalized.contains("html")) {
            return "HTML";
        }
        if (normalized.contains("xml")) {
            return "XML";
        }
        if (normalized.contains("markdown")) {
            return "Markdown";
        }
        return "Generated";
    }

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private Map<String, Object> exportEntry(ScanHistoryEntry entry) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", entry.id());
        output.put("recordedAt", entry.recordedAt() == null ? null : entry.recordedAt().toString());
        output.put("evidenceType", entry.evidenceType());
        output.put("operationKind", entry.operationKind());
        output.put("status", entry.status());
        output.put("engineId", entry.engineId());
        output.put("target", exportTarget(entry.target()));
        output.put("executionMode", entry.executionMode());
        output.put("backendReference", entry.backendReference());
        output.put("artifactId", entry.artifactId());
        output.put("artifactType", entry.artifactType());
        output.put("artifactLocation", entry.artifactLocation());
        output.put("mediaType", entry.mediaType());
        output.put("clientId", entry.clientId());
        output.put("workspaceId", entry.workspaceId());
        output.put("metadata", entry.metadata());
        return output;
    }

    private Map<String, Object> exportTarget(TargetDescriptor target) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (target == null) {
            output.put("kind", "WEB");
            output.put("baseUrl", null);
            output.put("displayName", "All targets");
            return output;
        }
        output.put("kind", target.kind() == null ? "WEB" : target.kind().name());
        output.put("baseUrl", target.baseUrl());
        output.put("displayName", target.displayName());
        return output;
    }

    private ScanHistoryEntry append(ScanHistoryEntry entry) {
        pruneExpired();
        return historyStore.append(entry);
    }

    private ScanHistoryEntry loadEntry(String entryId) {
        if (entryId.startsWith("job:")) {
            String jobId = entryId.substring("job:".length());
            return scanJobStore.load(jobId)
                    .filter(this::canReadJob)
                    .map(this::fromScanJob)
                    .orElse(null);
        }
        return historyStore.load(entryId)
                .filter(this::canReadEntry)
                .orElse(null);
    }

    private List<ScanHistoryEntry> queryEntries(String evidenceType,
                                                String status,
                                                String targetContains,
                                                int limit) {
        pruneExpired();
        ScanHistoryQuery query = new ScanHistoryQuery(
                normalizeNullable(evidenceType),
                normalizeNullable(status),
                normalizeNullable(targetContains),
                currentWorkspaceId()
        );
        ArrayList<ScanHistoryEntry> entries = new ArrayList<>();
        entries.addAll(visibleStoredEntries(historyStore.list(query, limit)));
        entries.addAll(visibleJobEntries(query));
        return entries.stream()
                .filter(entry -> matches(query, entry))
                .sorted(ENTRY_ORDER)
                .limit(limit)
                .toList();
    }

    private List<ScanHistoryEntry> visibleStoredEntries(List<ScanHistoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .filter(this::canReadEntry)
                .toList();
    }

    private List<ScanHistoryEntry> visibleJobEntries(ScanHistoryQuery query) {
        List<ScanJob> jobs = scanJobStore.list();
        return jobs.stream()
                .filter(this::canReadJob)
                .map(this::fromScanJob)
                .filter(entry -> matches(query, entry))
                .toList();
    }

    private ScanHistoryEntry fromScanJob(ScanJob job) {
        String clientId = hasText(job.getRequesterId()) ? job.getRequesterId() : "anonymous";
        String targetUrl = job.getParameters().get("targetUrl");
        TargetDescriptor target = gatewayRecordFactory.optionalTarget(targetUrl, TargetDescriptor.Kind.WEB);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("attempts", Integer.toString(job.getAttempts()));
        metadata.put("maxAttempts", Integer.toString(job.getMaxAttempts()));
        metadata.put("lastKnownProgress", Integer.toString(job.getLastKnownProgress()));
        if (job.getQueuePosition() > 0) {
            metadata.put("queuePosition", Integer.toString(job.getQueuePosition()));
        }
        if (job.getIdempotencyKey() != null) {
            metadata.put("idempotencyKey", job.getIdempotencyKey());
        }
        if (job.getLastError() != null && !job.getLastError().isBlank()) {
            metadata.put("lastError", truncate(job.getLastError(), 300));
        }
        if (job.getType().name().endsWith("_AS_USER")) {
            metadata.put("authenticated", "true");
        }
        return new ScanHistoryEntry(
                "job:" + job.getId(),
                effectiveRecordedAt(job),
                TYPE_SCAN_JOB,
                job.getType().name().toLowerCase(Locale.ROOT),
                job.getStatus().name().toLowerCase(Locale.ROOT),
                ENGINE_ZAP,
                target,
                "queue",
                hasText(job.getZapScanId()) ? job.getZapScanId() : job.getId(),
                null,
                null,
                null,
                null,
                clientId,
                clientWorkspaceResolver.resolveWorkspaceId(clientId),
                metadata
        );
    }

    private boolean matches(ScanHistoryQuery query, ScanHistoryEntry entry) {
        if (query == null) {
            return true;
        }
        if (hasText(query.evidenceType()) && !query.evidenceType().equalsIgnoreCase(entry.evidenceType())) {
            return false;
        }
        if (hasText(query.status()) && !query.status().equalsIgnoreCase(entry.status())) {
            return false;
        }
        if (hasText(query.workspaceId()) && !query.workspaceId().equals(entry.workspaceId())) {
            return false;
        }
        if (hasText(query.targetContains())) {
            String needle = query.targetContains().trim().toLowerCase(Locale.ROOT);
            String haystack = String.join(" ",
                    safe(entry.target() == null ? null : entry.target().baseUrl()),
                    safe(entry.target() == null ? null : entry.target().displayName()),
                    safe(entry.artifactLocation())
            ).toLowerCase(Locale.ROOT);
            return haystack.contains(needle);
        }
        return true;
    }

    private boolean canReadJob(ScanJob job) {
        if (job == null) {
            return false;
        }
        if (scanJobAccessBoundary != null) {
            return scanJobAccessBoundary.canCurrentRequesterAccess(job);
        }
        return currentWorkspaceId().equals(workspaceIdForRequester(job.getRequesterId()));
    }

    private boolean canReadEntry(ScanHistoryEntry entry) {
        if (entry == null) {
            return false;
        }
        if (scanHistoryAccessBoundary != null) {
            return scanHistoryAccessBoundary.canCurrentRequesterAccess(entry);
        }
        String entryWorkspaceId = hasText(entry.workspaceId())
                ? entry.workspaceId().trim()
                : workspaceIdForRequester(entry.clientId());
        return currentWorkspaceId().equals(entryWorkspaceId);
    }

    private boolean targetContains(TargetDescriptor entryTarget, TargetDescriptor requestedTarget) {
        if (entryTarget == null || requestedTarget == null
                || !hasText(entryTarget.baseUrl()) || !hasText(requestedTarget.baseUrl())) {
            return false;
        }
        try {
            URI entryUri = URI.create(entryTarget.baseUrl().trim());
            URI requestedUri = URI.create(requestedTarget.baseUrl().trim());
            String entryAuthority = authorityKey(entryUri);
            String requestedAuthority = authorityKey(requestedUri);
            if (!entryAuthority.equals(requestedAuthority)) {
                return false;
            }
            String entryPath = normalizedPath(entryUri);
            String requestedPath = normalizedPath(requestedUri);
            return requestedPath.equals(entryPath)
                    || requestedPath.startsWith(entryPath.endsWith("/") ? entryPath : entryPath + "/");
        } catch (IllegalArgumentException ignored) {
            return requestedTarget.baseUrl().trim().equalsIgnoreCase(entryTarget.baseUrl().trim());
        }
    }

    private String authorityKey(URI uri) {
        String scheme = valueOrDefault(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        String host = valueOrDefault(uri.getHost(), "").toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return scheme + "://" + host + (port >= 0 ? ":" + port : "");
    }

    private String normalizedPath(URI uri) {
        String path = uri.getPath();
        if (!hasText(path)) {
            return "/";
        }
        return path.trim();
    }

    private String hostLabel(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (hasText(uri.getHost())) {
                return uri.getHost().toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the raw label for non-URI target descriptors.
        }
        return hasText(baseUrl) ? baseUrl.trim().toLowerCase(Locale.ROOT) : null;
    }

    private String workspaceIdForRequester(String requesterId) {
        return valueOrDefault(clientWorkspaceResolver.resolveWorkspaceId(requesterId), "default-workspace");
    }

    private Instant effectiveRecordedAt(ScanJob job) {
        if (job.getCompletedAt() != null) {
            return job.getCompletedAt();
        }
        if (job.getStartedAt() != null) {
            return job.getStartedAt();
        }
        return job.getCreatedAt();
    }

    private void pruneExpired() {
        int retentionDays = retentionDays();
        if (retentionDays <= 0) {
            return;
        }
        historyStore.deleteBefore(Instant.now().minusSeconds(retentionDays * 24L * 60L * 60L));
    }

    private int boundedLimit(Integer requestedLimit, int configuredMax) {
        int max = Math.max(1, configuredMax);
        if (requestedLimit == null) {
            return max;
        }
        if (requestedLimit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(requestedLimit, max);
    }

    private int retentionDays() {
        return Math.max(0, properties.getRetentionDays());
    }

    private String newLedgerId() {
        return "hist_" + UUID.randomUUID();
    }

    private String mediaTypeFor(String reportTemplate, String artifactLocation) {
        String value = (safe(reportTemplate) + " " + safe(artifactLocation)).toLowerCase(Locale.ROOT);
        if (value.contains("json")) {
            return "application/json";
        }
        if (value.contains("xml")) {
            return "application/xml";
        }
        if (value.contains("sarif")) {
            return "application/sarif+json";
        }
        if (value.endsWith(".md") || value.contains("markdown")) {
            return "text/markdown";
        }
        return "text/html";
    }

    private String targetLabel(TargetDescriptor target) {
        if (target == null) {
            return "All targets";
        }
        if (hasText(target.baseUrl())) {
            return target.baseUrl();
        }
        if (hasText(target.displayName())) {
            return target.displayName();
        }
        return "All targets";
    }

    private String currentClientId() {
        return valueOrDefault(clientWorkspaceResolver.resolveCurrentClientId(), "anonymous");
    }

    private String currentWorkspaceId() {
        return valueOrDefault(clientWorkspaceResolver.resolveCurrentWorkspaceId(), "default-workspace");
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private String normalize(String value, String fallback) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT).replace(' ', '_') : fallback;
    }

    private String normalizeNullable(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String valueOrDefault(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
