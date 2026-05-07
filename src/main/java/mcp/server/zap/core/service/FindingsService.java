package mcp.server.zap.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineFindingAccess;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tools for detailed alert inspection beyond the high-level findings summary.
 */
@Service
public class FindingsService {
    private static final int DEFAULT_INSTANCE_LIMIT = 20;
    private static final int MAX_INSTANCE_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(FindingsService.class);

    private final EngineFindingAccess engineFindingAccess;
    private final ObjectMapper objectMapper;
    private ScanHistoryLedgerService scanHistoryLedgerService;

    public FindingsService(EngineFindingAccess engineFindingAccess) {
        this.engineFindingAccess = engineFindingAccess;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired(required = false)
    void setScanHistoryLedgerService(ScanHistoryLedgerService scanHistoryLedgerService) {
        this.scanHistoryLedgerService = scanHistoryLedgerService;
    }

    public String getFindingsSummary(String baseUrl) {
        List<AlertSnapshot> alerts = loadAlerts(baseUrl);
        if (alerts.isEmpty()) {
            return "✅ **Scan Complete**: No alerts found.";
        }

        Map<String, Map<String, Integer>> riskGroups = new LinkedHashMap<>();
        Map<String, String> alertDescriptions = new LinkedHashMap<>();

        for (AlertSnapshot alert : alerts) {
            String risk = displayValue(alert.risk());
            String alertName = displayValue(alert.name());
            riskGroups.computeIfAbsent(risk, ignored -> new LinkedHashMap<>())
                    .merge(alertName, 1, Integer::sum);
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
                    .append("  Instances: ").append(instances.size()).append('\n')
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
        FindingsSnapshot snapshot = buildSnapshot(baseUrl);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
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
        FindingsSnapshot current = buildSnapshot(baseUrl);

        Set<String> baselineFingerprints = new HashSet<>(baseline.fingerprints().stream()
                .map(FindingFingerprint::fingerprint)
                .toList());
        Set<String> currentFingerprints = new HashSet<>(current.fingerprints().stream()
                .map(FindingFingerprint::fingerprint)
                .toList());

        List<FindingFingerprint> newFindings = current.fingerprints().stream()
                .filter(fingerprint -> !baselineFingerprints.contains(fingerprint.fingerprint()))
                .sorted(Comparator.comparingInt((FindingFingerprint fingerprint) -> riskRank(fingerprint.risk()))
                        .reversed()
                        .thenComparing(FindingFingerprint::alertName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::url, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<FindingFingerprint> resolvedFindings = baseline.fingerprints().stream()
                .filter(fingerprint -> !currentFingerprints.contains(fingerprint.fingerprint()))
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
                .append("Baseline Findings: ").append(baseline.fingerprints().size()).append('\n')
                .append("Current Findings: ").append(current.fingerprints().size()).append('\n')
                .append("New Findings: ").append(newFindings.size()).append('\n')
                .append("Resolved Findings: ").append(resolvedFindings.size()).append('\n')
                .append("Unchanged Findings: ")
                .append(currentFingerprints.stream().filter(baselineFingerprints::contains).count())
                .append('\n')
                .append('\n');

        appendDiffGroups(output, "New finding groups", newGroups, boundedLimit);
        appendDiffGroups(output, "Resolved finding groups", resolvedGroups, boundedLimit);

        if (newFindings.isEmpty()) {
            output.append("No net-new findings compared with the supplied baseline.\n");
        } else {
            output.append("Review the new finding groups above before failing a build or promotion gate.\n");
        }
        return output.toString().trim();
    }

    private List<AlertSnapshot> loadAlerts(String baseUrl) {
        String normalizedBaseUrl = requireAuthorizedBaseUrl(baseUrl);
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
                                              Map<String, Map<String, Integer>> riskGroups,
                                              Map<String, String> alertDescriptions) {
        Map<String, Integer> groupedAlerts = riskGroups.get(riskLevel);
        if (groupedAlerts == null || groupedAlerts.isEmpty()) {
            return;
        }

        output.append("## 🔴 ").append(riskLevel).append(" Risk\n");
        groupedAlerts.forEach((alertName, count) -> output.append("* **")
                .append(alertName)
                .append("** (")
                .append(count)
                .append(" instances)\n")
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
                .append("Instances: ").append(instances.size()).append('\n')
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

    private FindingsSnapshot buildSnapshot(String baseUrl) {
        List<FindingFingerprint> fingerprints = loadAlerts(baseUrl).stream()
                .map(alert -> new FindingFingerprint(
                        fingerprintFor(alert),
                        trimToNull(alert.pluginId()),
                        displayValue(alert.name()),
                        displayValue(alert.risk()),
                        displayValue(alert.confidence()),
                        displayValue(alert.url()),
                        displayValue(alert.param())
                ))
                .sorted(Comparator.comparingInt((FindingFingerprint fingerprint) -> riskRank(fingerprint.risk()))
                        .reversed()
                        .thenComparing(FindingFingerprint::alertName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::url, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(FindingFingerprint::param, String.CASE_INSENSITIVE_ORDER))
                .toList();

        return new FindingsSnapshot(
                1,
                hasText(baseUrl) ? baseUrl.trim() : null,
                java.time.Instant.now().toString(),
                fingerprints
        );
    }

    private FindingsSnapshot parseSnapshot(String baselineSnapshot) {
        try {
            FindingsSnapshot snapshot = objectMapper.readValue(baselineSnapshot, FindingsSnapshot.class);
            if (snapshot.version() != 1) {
                throw new IllegalArgumentException("Unsupported findings snapshot version: " + snapshot.version());
            }
            if (snapshot.fingerprints() == null) {
                throw new IllegalArgumentException("Baseline snapshot is missing fingerprints");
            }
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("baselineSnapshot must be valid JSON exported by zap_findings_snapshot", e);
        }
    }

    private Map<DiffGroupKey, Long> groupDiffs(List<FindingFingerprint> fingerprints) {
        Map<DiffGroupKey, Long> groups = new LinkedHashMap<>();
        for (FindingFingerprint fingerprint : fingerprints) {
            DiffGroupKey key = new DiffGroupKey(
                    fingerprint.pluginId(),
                    fingerprint.alertName(),
                    fingerprint.risk()
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
                    .append(" | Count: ").append(entry.getValue())
                    .append('\n');
            index++;
        }
        output.append('\n');
    }

    private String fingerprintFor(AlertSnapshot alert) {
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
                                    List<FindingFingerprint> fingerprints) {
    }

    private record FindingFingerprint(String fingerprint,
                                      String pluginId,
                                      String alertName,
                                      String risk,
                                      String confidence,
                                      String url,
                                      String param) {
    }

    private record DiffGroupKey(String pluginId, String alertName, String risk) {
    }
}
