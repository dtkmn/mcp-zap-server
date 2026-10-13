package mcp.server.zap.core.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import mcp.gateway.core.url.UrlScope;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.AddOn;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.ScannerInventory;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tools for detailed alert inspection beyond the high-level findings summary.
 */
@Service
public class FindingsService {
    private static final int DEFAULT_INSTANCE_LIMIT = 20;
    private static final int MAX_INSTANCE_LIMIT = 100;
    private static final String SCANNER_COMPARISON_MATCHING = "matching";
    private static final String UNKNOWN_VALUE = "unknown";
    private static final String VERSION_FIELD = "version";
    private static final Logger log = LoggerFactory.getLogger(FindingsService.class);

    private final EngineFindingAccess engineFindingAccess;
    private final ObjectMapper objectMapper;
    private ScanHistoryLedgerService scanHistoryLedgerService;
    private EngineRuntimeAccess runtimeAccess;
    private BuildProperties buildProperties;
    private GitProperties gitProperties;

    public FindingsService(EngineFindingAccess engineFindingAccess) {
        this.engineFindingAccess = engineFindingAccess;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    @Autowired(required = false)
    void setRuntimeAccess(EngineRuntimeAccess runtimeAccess) {
        this.runtimeAccess = runtimeAccess;
    }

    @Autowired(required = false)
    void setBuildProperties(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Autowired(required = false)
    void setGitProperties(GitProperties gitProperties) {
        this.gitProperties = gitProperties;
    }

    public String getFindingsSummary(String baseUrl) {
        List<AlertSnapshot> alerts = loadAlerts(baseUrl);
        if (alerts.isEmpty()) {
            return "✅ **Scan Complete**: No alerts found.";
        }

        Map<String, Map<String, List<AlertSnapshot>>> riskGroups = new LinkedHashMap<>();
        Map<String, String> alertDescriptions = new LinkedHashMap<>();

        for (AlertSnapshot alert : alerts) {
            String risk = displayValue(alert.risk());
            String alertName = displayValue(alert.name());
            riskGroups.computeIfAbsent(risk, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(alertName, ignored -> new ArrayList<>()).add(alert);
            alertDescriptions.putIfAbsent(alertName, summarizeDescription(alert.description()));
        }

        StringBuilder output = new StringBuilder();
        output.append("# 🛡️ Scan Findings Summary\n\n");
        output.append("**Target:** ").append(hasText(baseUrl) ? baseUrl.trim() : "All Targets").append('\n');
        output.append("**Total Alerts:** ").append(alerts.size()).append("\n\n");

        appendFindingsSummarySection(output, "High", riskGroups, alertDescriptions);
        appendFindingsSummarySection(output, "Medium", riskGroups, alertDescriptions);
        appendFindingsSummarySection(output, "Low", riskGroups, alertDescriptions);
        appendFindingsSummarySection(output, "Informational", riskGroups, alertDescriptions);

        return output.toString();
    }

    public String getAlertDetails(
            String baseUrl,
            String pluginId,
            String alertName
    ) {
        List<AlertSnapshot> alerts = filterAlerts(loadAlerts(baseUrl), pluginId, alertName);
        if (alerts.isEmpty()) {
            return "No alerts matched the current filter.";
        }

        Map<AlertGroupKey, List<AlertSnapshot>> groupedAlerts = groupAlerts(alerts);
        List<Map.Entry<AlertGroupKey, List<AlertSnapshot>>> groups = groupedAlerts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<AlertGroupKey, List<AlertSnapshot>>>comparingInt(entry -> riskRank(entry.getKey().risk()))
                        .reversed()
                        .thenComparing(entry -> entry.getKey().alertName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (groups.size() == 1) {
            return formatDetailedGroup(groups.getFirst().getKey(), groups.getFirst().getValue(), baseUrl);
        }

        StringBuilder output = new StringBuilder();
        output.append("Alert detail groups: ")
                .append(groups.size())
                .append('\n')
                .append("Target: ")
                .append(hasText(baseUrl) ? baseUrl.trim() : "All targets")
                .append('\n');
        if (hasText(pluginId)) {
            output.append("Plugin ID Filter: ").append(pluginId.trim()).append('\n');
        }
        if (hasText(alertName)) {
            output.append("Alert Name Filter: ").append(alertName.trim()).append('\n');
        }
        output.append('\n');

        for (Map.Entry<AlertGroupKey, List<AlertSnapshot>> entry : groups) {
            AlertGroupKey key = entry.getKey();
            List<AlertSnapshot> instances = entry.getValue();
            AlertSnapshot sample = instances.getFirst();
            output.append("- Alert Name: ").append(key.alertName()).append('\n')
                    .append("  Plugin ID: ").append(displayValue(key.pluginId())).append('\n')
                    .append("  Instances: ").append(instanceCount(instances)).append('\n')
                    .append("  Risk: ").append(displayValue(key.risk())).append('\n')
                    .append("  Confidence: ").append(displayValue(key.confidence())).append('\n')
                    .append("  CWE ID: ").append(displayValue(sample.cweId())).append('\n')
                    .append("  WASC ID: ").append(displayValue(sample.wascId())).append('\n')
                    .append("  Sample URL: ").append(displayValue(sample.url())).append('\n');
        }

        output.append('\n')
                .append("Use the bounded instance view with pluginId or alertName to inspect concrete occurrences.");
        return output.toString().trim();
    }

    public String getAlertInstances(
            String baseUrl,
            String pluginId,
            String alertName,
            Integer limit
    ) {
        int boundedLimit = validateLimit(limit);
        List<AlertSnapshot> filteredAlerts = filterAlerts(loadAlerts(baseUrl), pluginId, alertName).stream()
                .sorted(Comparator.comparingInt((AlertSnapshot alert) -> riskRank(alert.risk()))
                        .reversed()
                        .thenComparing(AlertSnapshot::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(alert -> displayValue(alert.url()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (filteredAlerts.isEmpty()) {
            return "No alert instances matched the current filter.";
        }

        int returnedCount = Math.min(boundedLimit, filteredAlerts.size());
        List<AlertSnapshot> selectedAlerts = filteredAlerts.subList(0, returnedCount);

        StringBuilder output = new StringBuilder();
        output.append("Alert instances returned: ")
                .append(returnedCount)
                .append(" of ")
                .append(filteredAlerts.size())
                .append('\n')
                .append("Target: ")
                .append(hasText(baseUrl) ? baseUrl.trim() : "All targets")
                .append('\n');
        if (hasText(pluginId)) {
            output.append("Plugin ID Filter: ").append(pluginId.trim()).append('\n');
        }
        if (hasText(alertName)) {
            output.append("Alert Name Filter: ").append(alertName.trim()).append('\n');
        }
        output.append('\n');

        for (AlertSnapshot alert : selectedAlerts) {
            output.append("- Alert ID: ").append(displayValue(alert.id()))
                    .append(" | Plugin ID: ").append(displayValue(alert.pluginId()))
                    .append(" | Name: ").append(displayValue(alert.name()))
                    .append(" | Risk: ").append(displayValue(alert.risk()))
                    .append(" | Confidence: ").append(displayValue(alert.confidence()))
                    .append('\n')
                    .append("  URL: ").append(displayValue(alert.url())).append('\n')
                    .append("  Param: ").append(displayValue(alert.param())).append('\n')
                    .append("  Attack: ").append(compact(alert.attack())).append('\n')
                    .append("  Evidence: ").append(compact(alert.evidence())).append('\n')
                    .append("  Message ID: ").append(displayValue(alert.messageId())).append('\n');
            if (hasText(alert.nodeName())) {
                output.append("  Node Name: ").append(alert.nodeName()).append('\n');
            }
            if (hasText(alert.method())) {
                output.append("  Method: ").append(alert.method()).append('\n');
            }
            if (!alert.tags().isEmpty()) {
                output.append("  Tags: ").append(new TreeMap<>(alert.tags())).append('\n');
            }
        }

        if (selectedAlerts.stream().anyMatch(this::isSystemic)) {
            output.append("Systemic alerts are typically site-wide; recorded instances do not measure all affected endpoints.\n");
        }
        if (filteredAlerts.size() > returnedCount) {
            output.append('\n')
                    .append("Results truncated. Increase 'limit' up to ")
                    .append(MAX_INSTANCE_LIMIT)
                    .append(" for more instances.");
        }
        return output.toString().trim();
    }

    public String exportFindingsSnapshot(
            String baseUrl
    ) {
        FindingsSnapshot snapshot = buildSnapshot(baseUrl, 3);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        } catch (JacksonException e) {
            log.error("Error serializing findings snapshot for base URL {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Error serializing findings snapshot", e);
        }
    }

    public String diffFindings(
            String baseUrl,
            String baselineSnapshot,
            Integer maxGroups
    ) {
        if (!hasText(baselineSnapshot)) {
            throw new IllegalArgumentException("baselineSnapshot cannot be null or blank");
        }

        int boundedLimit = validateLimit(maxGroups == null ? 25 : maxGroups);
        FindingsSnapshot baseline = parseSnapshot(baselineSnapshot);
        FindingsSnapshot current = buildSnapshot(baseUrl, baseline.version() == 1 ? 1 : 3);
        String scannerComparison = compareScanners(baseline.scanner(), current.scanner());
        boolean comparable = SCANNER_COMPARISON_MATCHING.equals(scannerComparison);
        Map<String, FindingFingerprint> baselineFingerprints = uniqueFindings(baseline);
        Map<String, FindingFingerprint> currentFingerprints = uniqueFindings(current);

        List<FindingFingerprint> newFindings = currentFingerprints.values().stream()
                .filter(fingerprint -> !baselineFingerprints.containsKey(fingerprint.fingerprint()))
                .sorted(Comparator.comparingInt((FindingFingerprint fingerprint) -> riskRank(fingerprint.risk()))
                        .reversed()
                        .thenComparing(FindingFingerprint::alertName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::url, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<FindingFingerprint> resolvedFindings = baselineFingerprints.values().stream()
                .filter(fingerprint -> !currentFingerprints.containsKey(fingerprint.fingerprint()))
                .sorted(Comparator.comparingInt((FindingFingerprint fingerprint) -> riskRank(fingerprint.risk()))
                        .reversed()
                        .thenComparing(FindingFingerprint::alertName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::url, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<DiffGroupKey, Long> newGroups = groupDiffs(newFindings);
        Map<DiffGroupKey, Long> resolvedGroups = groupDiffs(resolvedFindings);

        StringBuilder output = new StringBuilder();
        output.append("Findings diff summary").append('\n')
                .append("Target: ").append(hasText(baseUrl) ? baseUrl.trim() : "All targets").append('\n')
                .append("Baseline Exported At: ").append(baseline.exportedAt()).append('\n')
                .append("Current Exported At: ").append(current.exportedAt()).append('\n')
                .append("Baseline Findings: ").append(baselineFingerprints.size()).append('\n')
                .append("Current Findings: ").append(currentFingerprints.size()).append('\n')
                .append("Scanner Comparison: ").append(scannerComparison).append('\n');
        appendScannerSummary(output, "Baseline", baseline.scanner());
        appendScannerSummary(output, "Current", current.scanner());
        if (comparable) {
            output.append("New Findings: ").append(newFindings.size()).append('\n')
                    .append("Resolved Findings: ").append(resolvedFindings.size()).append('\n')
                    .append("Unchanged Findings: ")
                    .append(currentFingerprints.keySet().stream().filter(baselineFingerprints::containsKey).count())
                    .append('\n')
                    .append("Matching scanner inventory does not establish identical scan coverage or prove application remediation.\n\n");
        } else {
            output.append("New Findings: unknown\nResolved Findings: unknown\nUnchanged Findings: unknown\n")
                    .append("Observed Added Findings: ").append(newFindings.size()).append('\n')
                    .append("Observed Removed Findings: ").append(resolvedFindings.size()).append('\n')
                    .append("Observed Retained Findings: ")
                    .append(currentFingerprints.keySet().stream().filter(baselineFingerprints::containsKey).count())
                    .append('\n')
                    .append("Scanner inventories differ or are incomplete. These observations cannot establish remediation or a net-new findings gate. Review and export a new baseline.\n\n");
        }

        if (baseline.version() == 1) {
            output.append("Legacy version 1 baseline: comparison uses URL-based identity. Export a new baseline to use ZAP node identity.\n\n");
        }
        appendDiffGroups(output, comparable ? "New finding groups" : "Observed added finding groups", newGroups, boundedLimit);
        appendDiffGroups(output, comparable ? "Resolved finding groups" : "Observed removed finding groups", resolvedGroups, boundedLimit);

        if (!comparable) {
            output.append("Baseline comparison requires complete matching scanner inventories.\n");
        } else if (newFindings.isEmpty()) {
            output.append("No net-new findings compared with the supplied baseline.\n");
        } else {
            output.append("Review the new finding groups above before failing a build or promotion gate.\n");
        }
        return output.toString().trim();
    }

    private List<AlertSnapshot> loadAlerts(String baseUrl) {
        return loadAuthorizedAlerts(requireAuthorizedBaseUrl(baseUrl));
    }

    private List<AlertSnapshot> loadAuthorizedAlerts(String normalizedBaseUrl) {
        UrlScope scope = UrlScope.parse(normalizedBaseUrl);
        return engineFindingAccess.loadAlerts(normalizedBaseUrl).stream()
                .filter(alert -> scope.contains(alert.url()))
                .toList();
    }

    private String requireAuthorizedBaseUrl(String baseUrl) {
        String normalizedBaseUrl = trimToNull(baseUrl);
        if (normalizedBaseUrl == null) {
            throw new IllegalArgumentException("baseUrl is required for findings reads; global ZAP findings are not exposed to ordinary clients.");
        }
        if (scanHistoryLedgerService == null
                || !scanHistoryLedgerService.hasVisibleScanEvidenceForTarget(normalizedBaseUrl)) {
            throw new IllegalArgumentException("No visible scan evidence exists for baseUrl: " + normalizedBaseUrl);
        }
        return normalizedBaseUrl;
    }

    private List<AlertSnapshot> filterAlerts(List<AlertSnapshot> alerts,
                                             String pluginId,
                                             String alertName) {
        String normalizedPluginId = trimToNull(pluginId);
        String normalizedAlertName = trimToNull(alertName);
        return alerts.stream()
                .filter(alert -> normalizedPluginId == null || normalizedPluginId.equals(alert.pluginId()))
                .filter(alert -> normalizedAlertName == null
                        || (alert.name() != null && alert.name().equalsIgnoreCase(normalizedAlertName)))
                .toList();
    }

    private Map<AlertGroupKey, List<AlertSnapshot>> groupAlerts(List<AlertSnapshot> alerts) {
        Map<AlertGroupKey, List<AlertSnapshot>> grouped = new LinkedHashMap<>();
        for (AlertSnapshot alert : alerts) {
            AlertGroupKey key = new AlertGroupKey(
                    trimToNull(alert.pluginId()),
                    displayValue(alert.name()),
                    displayValue(alert.risk()),
                    displayValue(alert.confidence())
            );
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(alert);
        }
        return grouped;
    }

    private void appendFindingsSummarySection(StringBuilder output,
                                              String riskLevel,
                                              Map<String, Map<String, List<AlertSnapshot>>> riskGroups,
                                              Map<String, String> alertDescriptions) {
        Map<String, List<AlertSnapshot>> groupedAlerts = riskGroups.get(riskLevel);
        if (groupedAlerts == null || groupedAlerts.isEmpty()) {
            return;
        }

        output.append("## 🔴 ").append(riskLevel).append(" Risk\n");
        groupedAlerts.forEach((alertName, instances) -> output.append("* **")
                .append(alertName)
                .append("** (")
                .append(instanceCount(instances))
                .append(instances.stream().anyMatch(this::isSystemic) ? ")\n" : " instances)\n")
                .append("  > ")
                .append(alertDescriptions.get(alertName))
                .append('\n'));
        output.append('\n');
    }

    private String formatDetailedGroup(AlertGroupKey key,
                                       List<AlertSnapshot> instances,
                                       String baseUrl) {
        AlertSnapshot sample = instances.getFirst();
        return new StringBuilder()
                .append("Alert details").append('\n')
                .append("Target: ").append(hasText(baseUrl) ? baseUrl.trim() : "All targets").append('\n')
                .append("Alert Name: ").append(key.alertName()).append('\n')
                .append("Plugin ID: ").append(displayValue(key.pluginId())).append('\n')
                .append("Instances: ").append(instanceCount(instances)).append('\n')
                .append("Risk: ").append(displayValue(key.risk())).append('\n')
                .append("Confidence: ").append(displayValue(key.confidence())).append('\n')
                .append("CWE ID: ").append(displayValue(sample.cweId())).append('\n')
                .append("WASC ID: ").append(displayValue(sample.wascId())).append('\n')
                .append("Description: ").append(compact(sample.description())).append('\n')
                .append("Solution: ").append(compact(sample.solution())).append('\n')
                .append("Reference: ").append(compact(sample.reference())).append('\n')
                .append("Sample URL: ").append(displayValue(sample.url())).append('\n')
                .append("Sample Param: ").append(displayValue(sample.param())).append('\n')
                .append("Sample Attack: ").append(compact(sample.attack())).append('\n')
                .append("Sample Evidence: ").append(compact(sample.evidence())).append('\n')
                .append("Inspect bounded instances when you need per-occurrence URLs, params, evidence, and message IDs.")
                .toString();
    }

    private int validateLimit(Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_INSTANCE_LIMIT : limit;
        if (effectiveLimit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(effectiveLimit, MAX_INSTANCE_LIMIT);
    }

    private int riskRank(String risk) {
        if (risk == null || risk.isBlank() || "<none>".equals(risk)) {
            return -1;
        }
        return switch (risk.trim().toLowerCase(Locale.ROOT)) {
            case "informational" -> 0;
            case "low" -> 1;
            case "medium" -> 2;
            case "high" -> 3;
            default -> -1;
        };
    }

    private String displayValue(Object value) {
        if (value == null) {
            return "<none>";
        }
        String rendered = value.toString().trim();
        return rendered.isEmpty() ? "<none>" : rendered;
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) {
            return "<none>";
        }
        String compacted = value.replaceAll("\\s+", " ").trim();
        return compacted.length() > 400 ? compacted.substring(0, 397) + "..." : compacted;
    }

    private String summarizeDescription(String description) {
        if (!hasText(description)) {
            return "No description";
        }
        String[] lines = description.trim().split("\\R", 2);
        return compact(lines[0]);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean isSystemic(AlertSnapshot alert) {
        return alert.tags().containsKey("SYSTEMIC");
    }

    private String instanceCount(List<AlertSnapshot> instances) {
        return instances.stream().anyMatch(this::isSystemic)
                ? "Systemic; recorded alerts: " + instances.size() + "; typically site-wide"
                : Integer.toString(instances.size());
    }

    private FindingsSnapshot buildSnapshot(String baseUrl, int version) {
        // Authorize before measuring the shared engine; do not expose inventory to an unscoped reader.
        String authorizedBaseUrl = requireAuthorizedBaseUrl(baseUrl);
        ScannerProvenance before = readScannerProvenance();
        List<FindingFingerprint> fingerprints = loadAuthorizedAlerts(authorizedBaseUrl).stream()
                .map(alert -> new FindingFingerprint(
                        fingerprintFor(alert, version),
                        trimToNull(alert.pluginId()),
                        displayValue(alert.name()),
                        displayValue(alert.risk()),
                        displayValue(alert.confidence()),
                        displayValue(alert.url()),
                        displayValue(alert.param()),
                        trimToNull(alert.nodeName()),
                        trimToNull(alert.method()),
                        new TreeMap<>(alert.tags())
                ))
                .sorted(Comparator.comparingInt((FindingFingerprint fingerprint) -> riskRank(fingerprint.risk()))
                        .reversed()
                        .thenComparing(FindingFingerprint::alertName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::url, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::param, String.CASE_INSENSITIVE_ORDER))
                .toList();

        ScannerProvenance after = readScannerProvenance();
        if (!SCANNER_COMPARISON_MATCHING.equals(compareScanners(before, after))) {
            after = after.withIncompleteInventory();
        }
        return new FindingsSnapshot(
                version,
                hasText(baseUrl) ? baseUrl.trim() : null,
                java.time.Instant.now().toString(),
                fingerprints,
                after
        );
    }

    private ScannerProvenance readScannerProvenance() {
        ScannerInventory inventory = ScannerInventory.unknown();
        if (runtimeAccess != null) {
            try {
                ScannerInventory measured = runtimeAccess.readScannerInventory();
                if (measured != null) {
                    inventory = measured;
                }
            } catch (RuntimeException _) {
                // Findings remain readable; do not publish upstream errors or claim a complete inventory.
            }
        }
        return new ScannerProvenance(1,
                safeIdentity(buildProperties == null ? null : buildProperties.getVersion(), "[A-Za-z0-9][A-Za-z0-9._+-]{0,127}"),
                safeIdentity(gitProperties == null ? null : gitProperties.getCommitId(), "[a-fA-F0-9]{7,64}"),
                null, null, inventory.zapVersion(), inventory.addOns(), inventory.inventoryComplete());
    }

    private static String safeIdentity(String value, String pattern) {
        return value != null && value.trim().matches(pattern) ? value.trim() : null;
    }

    private String compareScanners(ScannerProvenance baseline, ScannerProvenance current) {
        if (baseline == null || current == null || baseline.version() != 1 || current.version() != 1
                || !baseline.inventoryComplete() || !current.inventoryComplete()) {
            return UNKNOWN_VALUE;
        }
        return baseline.zapVersion().equals(current.zapVersion()) && baseline.addOns().equals(current.addOns())
                ? SCANNER_COMPARISON_MATCHING : "different";
    }

    private void appendScannerSummary(StringBuilder output, String label, ScannerProvenance scanner) {
        output.append(label).append(" MCP ZAP Server Version: ")
                .append(scanner == null || scanner.serverVersion() == null ? UNKNOWN_VALUE : scanner.serverVersion()).append('\n')
                .append(label).append(" Server Revision: ")
                .append(scanner == null || scanner.serverRevision() == null ? UNKNOWN_VALUE : scanner.serverRevision()).append('\n')
                .append(label).append(" ZAP Version: ")
                .append(scanner == null || scanner.zapVersion() == null ? UNKNOWN_VALUE : scanner.zapVersion()).append('\n')
                .append(label).append(" Add-on Inventory Complete: ")
                .append(scanner != null && scanner.inventoryComplete() ? "yes" : "no").append('\n');
        if (scanner != null) {
            for (AddOn addOn : scanner.addOns()) {
                output.append("- ").append(addOn.id()).append(" ").append(addOn.version())
                        .append(" (").append(addOn.status()).append(")\n");
            }
        }
    }

    private FindingsSnapshot parseSnapshot(String baselineSnapshot) {
        try {
            JsonNode payload = objectMapper.readTree(baselineSnapshot);
            if (!(payload instanceof ObjectNode object) || !payload.path(VERSION_FIELD).isInt()) {
                throw new IllegalArgumentException("Baseline snapshot must be an object with an integer version");
            }
            // Parse provenance separately: scalar coercion must not turn malformed evidence into a complete inventory.
            JsonNode scannerPayload = object.remove("scanner");
            FindingsSnapshot snapshot = objectMapper.treeToValue(object, FindingsSnapshot.class);
            if (snapshot.version() != 1 && snapshot.version() != 2 && snapshot.version() != 3) {
                throw new IllegalArgumentException("Unsupported findings snapshot version: " + snapshot.version());
            }
            if (snapshot.fingerprints() == null) {
                throw new IllegalArgumentException("Baseline snapshot is missing fingerprints");
            }
            if (snapshot.fingerprints().stream().anyMatch(f -> f == null || !hasText(f.fingerprint()))) {
                throw new IllegalArgumentException("Baseline snapshot contains an invalid finding fingerprint");
            }
            if (snapshot.version() < 3) {
                return new FindingsSnapshot(snapshot.version(), snapshot.baseUrl(), snapshot.exportedAt(),
                        snapshot.fingerprints(), null);
            }
            return new FindingsSnapshot(snapshot.version(), snapshot.baseUrl(), snapshot.exportedAt(),
                    snapshot.fingerprints(), parseScanner(scannerPayload));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("baselineSnapshot must be valid JSON exported by zap_findings_snapshot", e);
        }
    }

    private ScannerProvenance parseScanner(JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.path(VERSION_FIELD).isInt()
                || payload.path(VERSION_FIELD).asInt() != 1) {
            return new ScannerProvenance(1, null, null, null, null, null, List.of(), false);
        }
        JsonNode addOns = payload.path("addOns");
        List<AddOn> inventory = null;
        if (addOns.isArray() && addOns.size() <= ScannerInventory.MAX_ADD_ONS) {
            inventory = new ArrayList<>();
            for (JsonNode addOn : addOns) {
                inventory.add(new AddOn(scannerText(addOn, "id"), scannerText(addOn, VERSION_FIELD), scannerText(addOn, "status")));
            }
        }
        boolean complete = payload.path("inventoryComplete").isBoolean()
                && payload.path("inventoryComplete").asBoolean();
        return new ScannerProvenance(1, scannerText(payload, "serverVersion"), scannerText(payload, "serverRevision"),
                scannerText(payload, "serverImageDigest"), scannerText(payload, "zapImageDigest"),
                scannerText(payload, "zapVersion"), inventory, complete);
    }

    private String scannerText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isString() ? value.asString() : null;
    }

    private Map<DiffGroupKey, Long> groupDiffs(List<FindingFingerprint> fingerprints) {
        Map<DiffGroupKey, Long> groups = new LinkedHashMap<>();
        for (FindingFingerprint fingerprint : fingerprints) {
            DiffGroupKey key = new DiffGroupKey(
                    fingerprint.pluginId(),
                    fingerprint.alertName(),
                    fingerprint.risk(),
                    fingerprint.tags() != null && fingerprint.tags().containsKey("SYSTEMIC")
            );
            groups.put(key, groups.getOrDefault(key, 0L) + 1L);
        }
        return groups.entrySet().stream()
                .sorted(Comparator.<Map.Entry<DiffGroupKey, Long>>comparingInt(entry -> riskRank(entry.getKey().risk()))
                        .reversed()
                        .thenComparing(entry -> entry.getKey().alertName(), String.CASE_INSENSITIVE_ORDER))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private void appendDiffGroups(StringBuilder output,
                                  String heading,
                                  Map<DiffGroupKey, Long> groups,
                                  int maxGroups) {
        output.append(heading).append(": ").append(groups.size()).append('\n');
        if (groups.isEmpty()) {
            output.append("- none").append('\n').append('\n');
            return;
        }

        int index = 0;
        for (Map.Entry<DiffGroupKey, Long> entry : groups.entrySet()) {
            if (index >= maxGroups) {
                output.append("- output truncated at ").append(maxGroups).append(" groups").append('\n');
                break;
            }
            DiffGroupKey key = entry.getKey();
            output.append("- ")
                    .append(key.alertName())
                    .append(" | Risk: ").append(key.risk())
                    .append(" | Plugin ID: ").append(displayValue(key.pluginId()))
                    .append(key.systemic() ? " | Systemic; recorded findings: " : " | Count: ").append(entry.getValue())
                    .append('\n');
            index++;
        }
        output.append('\n');
    }

    private Map<String, FindingFingerprint> uniqueFindings(FindingsSnapshot snapshot) {
        Map<String, FindingFingerprint> findings = new LinkedHashMap<>();
        snapshot.fingerprints().forEach(finding -> findings.putIfAbsent(finding.fingerprint(), finding));
        return findings;
    }

    private String fingerprintFor(AlertSnapshot alert, int version) {
        if (version >= 2) {
            // nodeName is ZAP's opaque full endpoint identity; method is separate.
            // Keep a distinct URL fallback and encode components without delimiter ambiguity.
            return "v2:" + objectMapper.writeValueAsString(List.of(
                    displayValue(trimToNull(alert.pluginId())),
                    displayValue(alert.name()),
                    displayValue(alert.risk()),
                    displayValue(alert.confidence()),
                    hasText(alert.nodeName()) ? "node" : "url",
                    displayValue(hasText(alert.nodeName()) ? alert.nodeName() : alert.url()),
                    displayValue(alert.method()),
                    displayValue(alert.param())));
        }
        return String.join("||",
                displayValue(trimToNull(alert.pluginId())),
                displayValue(alert.name()),
                displayValue(alert.risk()),
                displayValue(alert.confidence()),
                displayValue(alert.url()),
                displayValue(alert.param()));
    }

    private record AlertGroupKey(String pluginId, String alertName, String risk, String confidence) {
    }

    private record FindingsSnapshot(int version,
                                    String baseUrl,
                                    String exportedAt,
                                    List<FindingFingerprint> fingerprints,
                                    ScannerProvenance scanner) {
    }

    private record ScannerProvenance(int version,
                                     String serverVersion,
                                     String serverRevision,
                                     String serverImageDigest,
                                     String zapImageDigest,
                                     String zapVersion,
                                     List<AddOn> addOns,
                                     boolean inventoryComplete) {
        ScannerProvenance {
            ScannerInventory inventory = new ScannerInventory(zapVersion, addOns, inventoryComplete);
            zapVersion = inventory.zapVersion();
            addOns = inventory.addOns();
            inventoryComplete = version == 1 && inventory.inventoryComplete();
            serverVersion = safeIdentity(serverVersion, "[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
            serverRevision = safeIdentity(serverRevision, "[a-fA-F0-9]{7,64}");
            serverImageDigest = safeIdentity(serverImageDigest, "sha256:[a-fA-F0-9]{64}");
            zapImageDigest = safeIdentity(zapImageDigest, "sha256:[a-fA-F0-9]{64}");
        }

        ScannerProvenance withIncompleteInventory() {
            return new ScannerProvenance(version, serverVersion, serverRevision, serverImageDigest,
                    zapImageDigest, zapVersion, addOns, false);
        }
    }

    private record FindingFingerprint(String fingerprint,
                                      String pluginId,
                                      String alertName,
                                      String risk,
                                      String confidence,
                                      String url,
                                      String param,
                                      String nodeName,
                                      String method,
                                      Map<String, String> tags) {
    }

    private record DiffGroupKey(String pluginId, String alertName, String risk, boolean systemic) {
    }
}
