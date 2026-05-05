package mcp.server.zap.core.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
        return historyStore.load(entryId).orElse(null);
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
                null
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
        if (scanHistoryAccessBoundary == null) {
            return entries;
        }
        return scanHistoryAccessBoundary.filterVisibleEntries(entries);
    }

    private List<ScanHistoryEntry> visibleJobEntries(ScanHistoryQuery query) {
        List<ScanJob> jobs = scanJobStore.list();
        if (scanJobAccessBoundary != null) {
            jobs = scanJobAccessBoundary.filterVisibleJobs(jobs);
        }
        return jobs.stream()
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
        return scanJobAccessBoundary == null || scanJobAccessBoundary.canCurrentRequesterAccess(job);
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
