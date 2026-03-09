package mcp.server.zap.core.service.jobstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Slf4j
public class PostgresScanJobStore implements ScanJobStore {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Comparator<ScanJob> JOB_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    private final ScanJobStoreProperties.Postgres properties;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public PostgresScanJobStore(ScanJobStoreProperties.Postgres properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.tableName = validateTableName(properties.getTableName());
    }

    @Override
    public void upsertAll(Collection<ScanJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (ScanJob job : jobs) {
                    if (job == null) {
                        continue;
                    }
                    upsertJob(connection, job);
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw new IllegalStateException("Postgres scan job upsert failed", e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            handleFailure("scan job upsert", e);
        }
    }

    @Override
    public Optional<ScanJob> load(String jobId) {
        try {
            String sql = "SELECT * FROM " + tableName + " WHERE job_id = ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, jobId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapRow(resultSet));
                }
            }
        } catch (Exception e) {
            handleFailure("scan job load", e);
            return Optional.empty();
        }
    }

    @Override
    public List<ScanJob> list() {
        try {
            try (Connection connection = openConnection()) {
                return loadAll(connection, false);
            }
        } catch (Exception e) {
            handleFailure("scan job list", e);
            return List.of();
        }
    }

    @Override
    public List<ScanJob> updateAndGet(UnaryOperator<List<ScanJob>> updater) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<ScanJob> currentJobs = loadAll(connection, true);
                List<ScanJob> updatedJobs = Objects.requireNonNull(
                        updater.apply(currentJobs),
                        "Scan job updater must not return null"
                );
                List<ScanJob> committedJobs = normalizeForCommit(updatedJobs);
                for (ScanJob job : committedJobs) {
                    if (job == null) {
                        continue;
                    }
                    upsertJob(connection, job);
                }
                connection.commit();
                return committedJobs;
            } catch (Exception e) {
                connection.rollback();
                throw new IllegalStateException("Postgres scan job transactional update failed", e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception e) {
            if (properties.isFailFast()) {
                throw new IllegalStateException("Postgres scan job transactional update failed", e);
            }
            log.warn("Postgres scan job transactional update failed (falling back to current view): {}", e.getMessage());
            return list();
        }
    }

    static List<ScanJob> normalizeForCommit(List<ScanJob> jobs) {
        ArrayList<ScanJob> committedJobs = new ArrayList<>();
        if (jobs != null) {
            for (ScanJob job : jobs) {
                if (job != null) {
                    committedJobs.add(job);
                }
            }
        }
        committedJobs.sort(JOB_ORDER);
        return committedJobs;
    }

    private void upsertJob(Connection connection, ScanJob job) throws Exception {
        String sql = "INSERT INTO " + tableName + " ("
                + "job_id, job_type, parameters_json, status, attempt_count, max_attempts, "
                + "zap_scan_id, last_error, created_at, started_at, completed_at, next_attempt_at, last_known_progress, queue_position, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (job_id) DO UPDATE SET "
                + "job_type = EXCLUDED.job_type, "
                + "parameters_json = EXCLUDED.parameters_json, "
                + "status = EXCLUDED.status, "
                + "attempt_count = EXCLUDED.attempt_count, "
                + "max_attempts = EXCLUDED.max_attempts, "
                + "zap_scan_id = EXCLUDED.zap_scan_id, "
                + "last_error = EXCLUDED.last_error, "
                + "created_at = EXCLUDED.created_at, "
                + "started_at = EXCLUDED.started_at, "
                + "completed_at = EXCLUDED.completed_at, "
                + "next_attempt_at = EXCLUDED.next_attempt_at, "
                + "last_known_progress = EXCLUDED.last_known_progress, "
                + "queue_position = EXCLUDED.queue_position, "
                + "updated_at = EXCLUDED.updated_at";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, job.getId());
            statement.setString(2, job.getType().name());
            statement.setString(3, objectMapper.writeValueAsString(job.getParameters()));
            statement.setString(4, job.getStatus().name());
            statement.setInt(5, job.getAttempts());
            statement.setInt(6, job.getMaxAttempts());
            statement.setString(7, job.getZapScanId());
            statement.setString(8, job.getLastError());
            statement.setTimestamp(9, Timestamp.from(job.getCreatedAt()));
            setTimestamp(statement, 10, job.getStartedAt());
            setTimestamp(statement, 11, job.getCompletedAt());
            setTimestamp(statement, 12, job.getNextAttemptAt());
            statement.setInt(13, job.getLastKnownProgress());
            statement.setInt(14, job.getQueuePosition());
            statement.setTimestamp(15, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private List<ScanJob> loadAll(Connection connection, boolean forUpdate) throws Exception {
        String sql = "SELECT * FROM " + tableName + " ORDER BY created_at, job_id"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ArrayList<ScanJob> jobs = new ArrayList<>();
            while (resultSet.next()) {
                jobs.add(mapRow(resultSet));
            }
            jobs.sort(JOB_ORDER);
            return jobs;
        }
    }

    private ScanJob mapRow(ResultSet resultSet) throws Exception {
        return ScanJob.restore(
                resultSet.getString("job_id"),
                ScanJobType.valueOf(resultSet.getString("job_type")),
                objectMapper.readValue(resultSet.getString("parameters_json"), STRING_MAP_TYPE),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getInt("max_attempts"),
                ScanJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                resultSet.getString("zap_scan_id"),
                resultSet.getString("last_error"),
                toInstant(resultSet.getTimestamp("started_at")),
                toInstant(resultSet.getTimestamp("completed_at")),
                toInstant(resultSet.getTimestamp("next_attempt_at")),
                resultSet.getInt("last_known_progress"),
                resultSet.getInt("queue_position")
        );
    }

    private Instant toInstant(Timestamp value) {
        return value != null ? value.toInstant() : null;
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setTimestamp(index, null);
            return;
        }
        statement.setTimestamp(index, Timestamp.from(value));
    }

    private Connection openConnection() throws SQLException {
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            return DriverManager.getConnection(properties.getUrl());
        }
        return DriverManager.getConnection(
                properties.getUrl(),
                properties.getUsername(),
                properties.getPassword()
        );
    }

    private String validateTableName(String configuredTableName) {
        String value = configuredTableName == null ? "" : configuredTableName.trim();
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid scan job table-name '" + configuredTableName + "'. Allowed pattern: [A-Za-z_][A-Za-z0-9_]*"
            );
        }
        return value;
    }

    private void handleFailure(String operation, Exception e) {
        if (properties.isFailFast()) {
            throw new IllegalStateException("Postgres " + operation + " failed", e);
        }
        log.warn("Postgres {} failed (continuing without durable scan-job sync): {}", operation, e.getMessage());
    }
}
