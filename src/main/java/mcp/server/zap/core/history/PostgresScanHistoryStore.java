package mcp.server.zap.core.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.gateway.TargetDescriptor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
public class PostgresScanHistoryStore implements ScanHistoryStore {
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ScanHistoryLedgerProperties.Postgres properties;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public PostgresScanHistoryStore(ScanHistoryLedgerProperties.Postgres properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.tableName = validateTableName(properties.getTableName());
    }

    @Override
    public ScanHistoryEntry append(ScanHistoryEntry entry) {
        String sql = "INSERT INTO " + tableName + " ("
                + "ledger_id, recorded_at, evidence_type, operation_kind, status, engine_id, "
                + "target_kind, target_url, target_display_name, execution_mode, backend_reference, "
                + "artifact_id, artifact_type, artifact_location, media_type, client_id, workspace_id, metadata_json"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (ledger_id) DO UPDATE SET "
                + "recorded_at = EXCLUDED.recorded_at, "
                + "evidence_type = EXCLUDED.evidence_type, "
                + "operation_kind = EXCLUDED.operation_kind, "
                + "status = EXCLUDED.status, "
                + "engine_id = EXCLUDED.engine_id, "
                + "target_kind = EXCLUDED.target_kind, "
                + "target_url = EXCLUDED.target_url, "
                + "target_display_name = EXCLUDED.target_display_name, "
                + "execution_mode = EXCLUDED.execution_mode, "
                + "backend_reference = EXCLUDED.backend_reference, "
                + "artifact_id = EXCLUDED.artifact_id, "
                + "artifact_type = EXCLUDED.artifact_type, "
                + "artifact_location = EXCLUDED.artifact_location, "
                + "media_type = EXCLUDED.media_type, "
                + "client_id = EXCLUDED.client_id, "
                + "workspace_id = EXCLUDED.workspace_id, "
                + "metadata_json = EXCLUDED.metadata_json";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindEntry(statement, entry);
            statement.executeUpdate();
            return entry;
        } catch (Exception e) {
            handleFailure("append", e);
            return entry;
        }
    }

    @Override
    public Optional<ScanHistoryEntry> load(String entryId) {
        String sql = "SELECT * FROM " + tableName + " WHERE ledger_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entryId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        } catch (Exception e) {
            handleFailure("load", e);
            return Optional.empty();
        }
    }

    @Override
    public List<ScanHistoryEntry> list(ScanHistoryQuery query, int limit) {
        QueryParts parts = buildQuery(query);
        String sql = "SELECT * FROM " + tableName + parts.whereClause()
                + " ORDER BY recorded_at DESC, ledger_id DESC LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String value : parts.bindings()) {
                statement.setString(index++, value);
            }
            statement.setInt(index, Math.max(0, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<ScanHistoryEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(mapRow(resultSet));
                }
                return entries;
            }
        } catch (Exception e) {
            handleFailure("list", e);
            return List.of();
        }
    }

    @Override
    public void deleteBefore(Instant cutoff) {
        if (cutoff == null) {
            return;
        }
        String sql = "DELETE FROM " + tableName + " WHERE recorded_at < ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.executeUpdate();
        } catch (Exception e) {
            handleFailure("retention prune", e);
        }
    }

    private void bindEntry(PreparedStatement statement, ScanHistoryEntry entry) throws Exception {
        TargetDescriptor target = entry.target();
        statement.setString(1, entry.id());
        statement.setTimestamp(2, Timestamp.from(entry.recordedAt()));
        statement.setString(3, entry.evidenceType());
        statement.setString(4, entry.operationKind());
        statement.setString(5, entry.status());
        statement.setString(6, entry.engineId());
        statement.setString(7, target == null || target.kind() == null ? null : target.kind().name());
        statement.setString(8, target == null ? null : target.baseUrl());
        statement.setString(9, target == null ? null : target.displayName());
        statement.setString(10, entry.executionMode());
        statement.setString(11, entry.backendReference());
        statement.setString(12, entry.artifactId());
        statement.setString(13, entry.artifactType());
        statement.setString(14, entry.artifactLocation());
        statement.setString(15, entry.mediaType());
        statement.setString(16, entry.clientId());
        statement.setString(17, entry.workspaceId());
        statement.setString(18, objectMapper.writeValueAsString(entry.metadata()));
    }

    private QueryParts buildQuery(ScanHistoryQuery query) {
        if (query == null) {
            return new QueryParts("", List.of());
        }
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> bindings = new ArrayList<>();
        if (hasText(query.evidenceType())) {
            clauses.add("LOWER(evidence_type) = LOWER(?)");
            bindings.add(query.evidenceType().trim());
        }
        if (hasText(query.status())) {
            clauses.add("LOWER(status) = LOWER(?)");
            bindings.add(query.status().trim());
        }
        if (hasText(query.workspaceId())) {
            clauses.add("workspace_id = ?");
            bindings.add(query.workspaceId().trim());
        }
        if (hasText(query.targetContains())) {
            clauses.add("LOWER(COALESCE(target_url, '') || ' ' || COALESCE(target_display_name, '') || ' ' "
                    + "|| COALESCE(artifact_location, '')) LIKE ?");
            bindings.add("%" + query.targetContains().trim().toLowerCase() + "%");
        }
        if (clauses.isEmpty()) {
            return new QueryParts("", List.of());
        }
        return new QueryParts(" WHERE " + String.join(" AND ", clauses), bindings);
    }

    private ScanHistoryEntry mapRow(ResultSet resultSet) throws Exception {
        String targetKind = resultSet.getString("target_kind");
        TargetDescriptor target = new TargetDescriptor(
                hasText(targetKind) ? TargetDescriptor.Kind.valueOf(targetKind) : TargetDescriptor.Kind.WEB,
                resultSet.getString("target_url"),
                resultSet.getString("target_display_name")
        );
        String metadataJson = resultSet.getString("metadata_json");
        Map<String, String> metadata = hasText(metadataJson)
                ? objectMapper.readValue(metadataJson, STRING_MAP_TYPE)
                : Map.of();
        return new ScanHistoryEntry(
                resultSet.getString("ledger_id"),
                resultSet.getTimestamp("recorded_at").toInstant(),
                resultSet.getString("evidence_type"),
                resultSet.getString("operation_kind"),
                resultSet.getString("status"),
                resultSet.getString("engine_id"),
                target,
                resultSet.getString("execution_mode"),
                resultSet.getString("backend_reference"),
                resultSet.getString("artifact_id"),
                resultSet.getString("artifact_type"),
                resultSet.getString("artifact_location"),
                resultSet.getString("media_type"),
                resultSet.getString("client_id"),
                resultSet.getString("workspace_id"),
                metadata
        );
    }

    private Connection openConnection() throws SQLException {
        if (!hasText(properties.getUsername())) {
            return DriverManager.getConnection(properties.getUrl());
        }
        return DriverManager.getConnection(properties.getUrl(), properties.getUsername(), properties.getPassword());
    }

    private String validateTableName(String configuredTableName) {
        String value = configuredTableName == null ? "" : configuredTableName.trim();
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid scan history table-name '" + configuredTableName
                            + "'. Allowed pattern: [A-Za-z_][A-Za-z0-9_]*"
            );
        }
        return value;
    }

    private void handleFailure(String operation, Exception e) {
        if (properties.isFailFast()) {
            throw new IllegalStateException("Postgres scan history " + operation + " failed", e);
        }
        log.warn("Postgres scan history {} failed (continuing without durable history sync): {}",
                operation, e.getMessage());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record QueryParts(String whereClause, List<String> bindings) {
    }
}
