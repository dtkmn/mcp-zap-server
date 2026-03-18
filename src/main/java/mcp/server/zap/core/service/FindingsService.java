package mcp.server.zap.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.exception.ZapApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tools for detailed alert inspection beyond the high-level findings summary.
 */
@Service
public class FindingsService {
    private static final int DEFAULT_INSTANCE_LIMIT = 20;
    private static final int MAX_INSTANCE_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(FindingsService.class);

    private final ClientApi zap;
    private final ObjectMapper objectMapper;

    public FindingsService(ClientApi zap) {
        this.zap = zap;
        this.objectMapper = new ObjectMapper();
    }

    public String getFindingsSummary(String baseUrl) {
        List<org.zaproxy.clientapi.core.Alert> alerts = loadAlerts(baseUrl);
        if (alerts.isEmpty()) {
            return "✅ **Scan Complete**: No alerts found.";
        }

        Map<String, Map<String, Integer>> riskGroups = new LinkedHashMap<>();
        Map<String, String> alertDescriptions = new LinkedHashMap<>();

        for (org.zaproxy.clientapi.core.Alert alert : alerts) {
            String risk = displayValue(alert.getRisk());
            String alertName = displayValue(alert.getName());
            riskGroups.computeIfAbsent(risk, ignored -> new LinkedHashMap<>())
                    .merge(alertName, 1, Integer::sum);
            alertDescriptions.putIfAbsent(alertName, summarizeDescription(alert.getDescription()));
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
        List<org.zaproxy.clientapi.core.Alert> alerts = filterAlerts(loadAlerts(baseUrl), pluginId, alertName);
        if (alerts.isEmpty()) {
            return "No alerts matched the current filter.";
        }

        Map<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>> groupedAlerts = groupAlerts(alerts);
        List<Map.Entry<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>>> groups = groupedAlerts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>>>comparingInt(entry -> riskRank(entry.getKey().risk()))
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

        for (Map.Entry<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>> entry : groups) {
            AlertGroupKey key = entry.getKey();
            List<org.zaproxy.clientapi.core.Alert> instances = entry.getValue();
            org.zaproxy.clientapi.core.Alert sample = instances.getFirst();
            output.append("- Alert Name: ").append(key.alertName()).append('\n')
                    .append("  Plugin ID: ").append(displayValue(key.pluginId())).append('\n')
                    .append("  Instances: ").append(instances.size()).append('\n')
                    .append("  Risk: ").append(displayValue(key.risk())).append('\n')
                    .append("  Confidence: ").append(displayValue(key.confidence())).append('\n')
                    .append("  CWE ID: ").append(sample.getCweId()).append('\n')
                    .append("  WASC ID: ").append(sample.getWascId()).append('\n')
                    .append("  Sample URL: ").append(displayValue(sample.getUrl())).append('\n');
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
        List<org.zaproxy.clientapi.core.Alert> filteredAlerts = filterAlerts(loadAlerts(baseUrl), pluginId, alertName).stream()
                .sorted(Comparator.comparingInt((org.zaproxy.clientapi.core.Alert alert) -> riskRank(alert.getRisk()))
                        .reversed()
                        .thenComparing(org.zaproxy.clientapi.core.Alert::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(alert -> displayValue(alert.getUrl()), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (filteredAlerts.isEmpty()) {
            return "No alert instances matched the current filter.";
        }

        int returnedCount = Math.min(boundedLimit, filteredAlerts.size());
        List<org.zaproxy.clientapi.core.Alert> selectedAlerts = filteredAlerts.subList(0, returnedCount);

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

        for (org.zaproxy.clientapi.core.Alert alert : selectedAlerts) {
            output.append("- Alert ID: ").append(displayValue(alert.getId()))
                    .append(" | Plugin ID: ").append(displayValue(alert.getPluginId()))
                    .append(" | Name: ").append(displayValue(alert.getName()))
                    .append(" | Risk: ").append(displayValue(alert.getRisk()))
                    .append(" | Confidence: ").append(displayValue(alert.getConfidence()))
                    .append('\n')
                    .append("  URL: ").append(displayValue(alert.getUrl())).append('\n')
                    .append("  Param: ").append(displayValue(alert.getParam())).append('\n')
                    .append("  Attack: ").append(compact(alert.getAttack())).append('\n')
                    .append("  Evidence: ").append(compact(alert.getEvidence())).append('\n')
                    .append("  Message ID: ").append(displayValue(alert.getMessageId())).append('\n');
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

    private List<org.zaproxy.clientapi.core.Alert> loadAlerts(String baseUrl) {
        try {
            ApiResponse response = zap.alert.alerts(trimToNull(baseUrl), "0", "-1", null, null, null);
            if (!(response instanceof ApiResponseList list)) {
                throw new IllegalStateException("Unexpected response from alert.alerts(): " + response);
            }
            List<org.zaproxy.clientapi.core.Alert> alerts = new ArrayList<>();
            for (ApiResponse item : list.getItems()) {
                if (item instanceof ApiResponseSet set) {
                    alerts.add(new org.zaproxy.clientapi.core.Alert(set));
                }
            }
            return alerts;
        } catch (ClientApiException e) {
            log.error("Error retrieving alerts for base URL {}: {}", baseUrl, e.getMessage(), e);
            throw new ZapApiException("Error retrieving detailed alerts", e);
        }
    }

    private List<org.zaproxy.clientapi.core.Alert> filterAlerts(List<org.zaproxy.clientapi.core.Alert> alerts,
                                                                String pluginId,
                                                                String alertName) {
        String normalizedPluginId = trimToNull(pluginId);
        String normalizedAlertName = trimToNull(alertName);
        return alerts.stream()
                .filter(alert -> normalizedPluginId == null || normalizedPluginId.equals(alert.getPluginId()))
                .filter(alert -> normalizedAlertName == null
                        || (alert.getName() != null && alert.getName().equalsIgnoreCase(normalizedAlertName)))
                .toList();
    }

    private Map<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>> groupAlerts(List<org.zaproxy.clientapi.core.Alert> alerts) {
        Map<AlertGroupKey, List<org.zaproxy.clientapi.core.Alert>> grouped = new LinkedHashMap<>();
        for (org.zaproxy.clientapi.core.Alert alert : alerts) {
            AlertGroupKey key = new AlertGroupKey(
                    trimToNull(alert.getPluginId()),
                    displayValue(alert.getName()),
                    displayValue(alert.getRisk()),
                    displayValue(alert.getConfidence())
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
                                       List<org.zaproxy.clientapi.core.Alert> instances,
                                       String baseUrl) {
        org.zaproxy.clientapi.core.Alert sample = instances.getFirst();
        return new StringBuilder()
                .append("Alert details").append('\n')
                .append("Target: ").append(hasText(baseUrl) ? baseUrl.trim() : "All targets").append('\n')
                .append("Alert Name: ").append(key.alertName()).append('\n')
                .append("Plugin ID: ").append(displayValue(key.pluginId())).append('\n')
                .append("Instances: ").append(instances.size()).append('\n')
                .append("Risk: ").append(displayValue(key.risk())).append('\n')
                .append("Confidence: ").append(displayValue(key.confidence())).append('\n')
                .append("CWE ID: ").append(sample.getCweId()).append('\n')
                .append("WASC ID: ").append(sample.getWascId()).append('\n')
                .append("Description: ").append(compact(sample.getDescription())).append('\n')
                .append("Solution: ").append(compact(sample.getSolution())).append('\n')
                .append("Reference: ").append(compact(sample.getReference())).append('\n')
                .append("Sample URL: ").append(displayValue(sample.getUrl())).append('\n')
                .append("Sample Param: ").append(displayValue(sample.getParam())).append('\n')
                .append("Sample Attack: ").append(compact(sample.getAttack())).append('\n')
                .append("Sample Evidence: ").append(compact(sample.getEvidence())).append('\n')
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

    private int riskRank(org.zaproxy.clientapi.core.Alert.Risk risk) {
        if (risk == null) {
            return -1;
        }
        return switch (risk) {
            case Informational -> 0;
            case Low -> 1;
            case Medium -> 2;
            case High -> 3;
        };
    }

    private int riskRank(String risk) {
        if (risk == null || risk.isBlank() || "<none>".equals(risk)) {
            return -1;
        }
        try {
            return riskRank(org.zaproxy.clientapi.core.Alert.Risk.valueOf(risk));
        } catch (IllegalArgumentException e) {
            return -1;
        }
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
                        trimToNull(alert.getPluginId()),
                        displayValue(alert.getName()),
                        displayValue(alert.getRisk()),
                        displayValue(alert.getConfidence()),
                        displayValue(alert.getUrl()),
                        displayValue(alert.getParam())
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

    private String fingerprintFor(org.zaproxy.clientapi.core.Alert alert) {
        return String.join("||",
                displayValue(trimToNull(alert.getPluginId())),
                displayValue(alert.getName()),
                displayValue(alert.getRisk()),
                displayValue(alert.getConfidence()),
                displayValue(alert.getUrl()),
                displayValue(alert.getParam()));
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
