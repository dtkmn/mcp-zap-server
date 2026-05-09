package mcp.server.zap.core.service.jobstore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.queue.ScanJobClaimToken;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

@Slf4j
public class PostgresScanJobStore implements ScanJobStore {
    private static final int MAX_TRANSACTION_RETRIES = 3;
    private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String SERIALIZATION_FAILURE_SQLSTATE = "40001";
    private static final String DEADLOCK_SQLSTATE = "40P01";

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
    public ScanJob admitQueuedJob(ScanJob candidate) {
        int attempt = 1;
        while (true) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    lockTableForQueueMutation(connection);

                    ScanJob existing = loadByRequesterAndIdempotencyKey(connection,
                            candidate.getRequesterId(),
                            candidate.getIdempotencyKey(),
                            true
                    ).orElse(null);
                    if (existing != null) {
                        connection.commit();
                        return existing;
                    }

                    candidate.assignQueuePosition(nextQueuePosition(connection));
                    upsertJob(connection, candidate);
                    connection.commit();
                    return candidate;
                } catch (Exception e) {
                    connection.rollback();
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying Postgres queued admission after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
                    throw new IllegalStateException("Postgres queued scan admission failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                if (properties.isFailFast()) {
                    throw new IllegalStateException("Postgres queued scan admission failed", e);
                }
                throw new IllegalStateException("Postgres queued scan admission failed", e);
            }
        }
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
    public Optional<ScanJob> loadByRequesterAndIdempotencyKey(String requesterId, String idempotencyKey) {
        if (!hasText(requesterId) || !hasText(idempotencyKey)) {
            return Optional.empty();
        }
        try (Connection connection = openConnection()) {
            return loadByRequesterAndIdempotencyKey(connection, requesterId, idempotencyKey, false);
        } catch (Exception e) {
            handleFailure("scan job idempotency lookup", e);
            return Optional.empty();
        }
    }

    @Override
    public List<ScanJob> claimRunningJobs(String workerId, Instant now, Instant claimUntil) {
        int attempt = 1;
        while (true) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    List<ScanJob> claimedJobs = loadClaimableRunningJobs(connection, workerId, now);
                    ArrayList<ScanJob> committedClaimedJobs = new ArrayList<>();
                    for (ScanJob job : claimedJobs) {
                        job.claim(workerId, now, claimUntil);
                        upsertJob(connection, job);
                        loadById(connection, job.getId(), false).ifPresent(committedClaimedJobs::add);
                    }
                    connection.commit();
                    committedClaimedJobs.sort(JOB_ORDER);
                    return committedClaimedJobs;
                } catch (Exception e) {
                    connection.rollback();
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying Postgres running-job claim after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
                    throw new IllegalStateException("Postgres running-job claim failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                if (properties.isFailFast()) {
                    throw new IllegalStateException("Postgres running-job claim failed", e);
                }
                log.warn("Postgres running-job claim failed (falling back to current view): {}", e.getMessage());
                return List.of();
            }
        }
    }

    @Override
    public List<ScanJob> claimQueuedJobs(
            String workerId,
            Instant now,
            Instant claimUntil,
            int maxConcurrentActiveScans,
            int maxConcurrentSpiderScans
    ) {
        int attempt = 1;
        while (true) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    lockTableForQueueMutation(connection);

                    int activeSlotsRemaining = Math.max(0,
                            maxConcurrentActiveScans - countCapacityInUse(connection, true, now));
                    int spiderSlotsRemaining = Math.max(0,
                            maxConcurrentSpiderScans - countCapacityInUse(connection, false, now));
                    boolean ajaxBusy = hasAjaxCapacityInUse(connection, now);

                    List<ScanJob> candidates = loadClaimableQueuedJobs(connection, now);
                    ArrayList<ScanJob> claimedJobs = new ArrayList<>();
                    for (ScanJob job : candidates) {
                        boolean activeFamily = job.getType().isActiveFamily();
                        if (activeFamily && activeSlotsRemaining <= 0) {
                            continue;
                        }
                        if (!activeFamily && spiderSlotsRemaining <= 0) {
                            continue;
                        }
                        if (job.getType() == ScanJobType.AJAX_SPIDER && ajaxBusy) {
                            continue;
                        }

                        job.claim(workerId, now, claimUntil);
                        upsertJob(connection, job);
                        loadById(connection, job.getId(), false).ifPresent(claimedJobs::add);

                        if (activeFamily) {
                            activeSlotsRemaining -= 1;
                        } else {
                            spiderSlotsRemaining -= 1;
                        }
                        if (job.getType() == ScanJobType.AJAX_SPIDER) {
                            ajaxBusy = true;
                        }
                    }

                    connection.commit();
                    return claimedJobs;
                } catch (Exception e) {
                    connection.rollback();
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying Postgres queued-job claim after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
                    throw new IllegalStateException("Postgres queued-job claim failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                if (properties.isFailFast()) {
                    throw new IllegalStateException("Postgres queued-job claim failed", e);
                }
                log.warn("Postgres queued-job claim failed (falling back to current view): {}", e.getMessage());
                return List.of();
            }
        }
    }

    @Override
    public int renewClaims(String workerId, Collection<String> jobIds, Instant now, Instant claimUntil) {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }

        int attempt = 1;
        while (true) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    int renewedCount = 0;
                    for (String jobId : jobIds) {
                        if (!hasText(jobId)) {
                            continue;
                        }
                        ScanJob job = loadById(connection, jobId, true).orElse(null);
                        if (job == null
                                || !job.isClaimedBy(workerId)
                                || !job.hasLiveClaim(now)
                                || job.getStatus().isTerminal()) {
                            continue;
                        }
                        job.claim(workerId, now, claimUntil);
                        upsertJob(connection, job);
                        renewedCount += 1;
                    }
                    connection.commit();
                    return renewedCount;
                } catch (Exception e) {
                    connection.rollback();
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying Postgres claim renewal after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
                    throw new IllegalStateException("Postgres claim renewal failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                handleFailure("claim renewal", e);
                return 0;
            }
        }
    }

    @Override
    public Optional<ScanJob> updateClaimedJob(
            String jobId,
            ScanJobClaimToken claimToken,
            Instant now,
            UnaryOperator<ScanJob> updater
    ) {
        int attempt = 1;
        while (true) {
            try (Connection connection = openConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScanJob current = loadById(connection, jobId, true).orElse(null);
                    if (current == null
                            || claimToken == null
                            || !claimToken.matches(current)
                            || !current.hasLiveClaim(now)) {
                        connection.commit();
                        return Optional.empty();
                    }

                    ScanJob updated = Objects.requireNonNull(updater.apply(current),
                            "Claimed scan job updater must not return null");
                    normalizeQueuePositionTransition(connection, updated);
                    upsertJob(connection, updated);
                    connection.commit();
                    return Optional.of(updated);
                } catch (Exception e) {
                    connection.rollback();
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying claimed scan-job update after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
                    throw new IllegalStateException("Postgres claimed scan-job update failed", e);
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (Exception e) {
                if (properties.isFailFast()) {
                    throw new IllegalStateException("Postgres claimed scan-job update failed", e);
                }
                log.warn("Postgres claimed scan-job update failed (keeping current durable view): {}", e.getMessage());
                return Optional.empty();
            }
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
        int attempt = 1;
        while (true) {
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
                    if (shouldRetryTransactionalUpdate(e, attempt)) {
                        log.info("Retrying Postgres scan job transactional update after SQL state {} (attempt {}/{})",
                                extractSqlState(e), attempt, MAX_TRANSACTION_RETRIES);
                        attempt += 1;
                        continue;
                    }
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
                + "job_id, job_type, parameters_json, status, attempt_count, max_attempts, requester_id, idempotency_key, "
                + "zap_scan_id, last_error, created_at, started_at, completed_at, next_attempt_at, last_known_progress, "
                + "queue_position, claim_owner_id, claim_fence_id, claim_heartbeat_at, claim_expires_at, updated_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (job_id) DO UPDATE SET "
                + "job_type = EXCLUDED.job_type, "
                + "parameters_json = EXCLUDED.parameters_json, "
                + "status = EXCLUDED.status, "
                + "attempt_count = EXCLUDED.attempt_count, "
                + "max_attempts = EXCLUDED.max_attempts, "
                + "requester_id = EXCLUDED.requester_id, "
                + "idempotency_key = EXCLUDED.idempotency_key, "
                + "zap_scan_id = EXCLUDED.zap_scan_id, "
                + "last_error = EXCLUDED.last_error, "
                + "created_at = EXCLUDED.created_at, "
                + "started_at = EXCLUDED.started_at, "
                + "completed_at = EXCLUDED.completed_at, "
                + "next_attempt_at = EXCLUDED.next_attempt_at, "
                + "last_known_progress = EXCLUDED.last_known_progress, "
                + "queue_position = EXCLUDED.queue_position, "
                + "claim_owner_id = EXCLUDED.claim_owner_id, "
                + "claim_fence_id = EXCLUDED.claim_fence_id, "
                + "claim_heartbeat_at = EXCLUDED.claim_heartbeat_at, "
                + "claim_expires_at = EXCLUDED.claim_expires_at, "
                + "updated_at = EXCLUDED.updated_at";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, job.getId());
            statement.setString(2, job.getType().name());
            statement.setString(3, objectMapper.writeValueAsString(job.getParameters()));
            statement.setString(4, job.getStatus().name());
            statement.setInt(5, job.getAttempts());
            statement.setInt(6, job.getMaxAttempts());
            statement.setString(7, job.getRequesterId());
            statement.setString(8, job.getIdempotencyKey());
            statement.setString(9, job.getZapScanId());
            statement.setString(10, job.getLastError());
            statement.setTimestamp(11, Timestamp.from(job.getCreatedAt()));
            setTimestamp(statement, 12, job.getStartedAt());
            setTimestamp(statement, 13, job.getCompletedAt());
            setTimestamp(statement, 14, job.getNextAttemptAt());
            statement.setInt(15, job.getLastKnownProgress());
            statement.setInt(16, job.getQueuePosition());
            statement.setString(17, job.getClaimOwnerId());
            statement.setString(18, job.getClaimFenceId());
            setTimestamp(statement, 19, job.getClaimHeartbeatAt());
            setTimestamp(statement, 20, job.getClaimExpiresAt());
            statement.setTimestamp(21, Timestamp.from(Instant.now()));
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

    private Optional<ScanJob> loadByRequesterAndIdempotencyKey(
            Connection connection,
            String requesterId,
            String idempotencyKey,
            boolean forUpdate
    ) throws Exception {
        if (!hasText(requesterId) || !hasText(idempotencyKey)) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM " + tableName + " WHERE requester_id = ? AND idempotency_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requesterId);
            statement.setString(2, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        }
    }

    private Optional<ScanJob> loadById(Connection connection, String jobId, boolean forUpdate) throws Exception {
        String sql = "SELECT * FROM " + tableName + " WHERE job_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(resultSet));
            }
        }
    }

    private int nextQueuePosition(Connection connection) throws Exception {
        String sql = "SELECT queue_position FROM " + tableName
                + " WHERE status = ? ORDER BY queue_position DESC NULLS LAST LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ScanJobStatus.QUEUED.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 1;
                }
                return Math.max(resultSet.getInt(1), 0) + 1;
            }
        }
    }

    private void lockTableForQueueMutation(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE " + tableName + " IN SHARE ROW EXCLUSIVE MODE")) {
            statement.execute();
        }
    }

    private List<ScanJob> loadClaimableRunningJobs(Connection connection, String workerId, Instant now) throws Exception {
        String sql = "SELECT * FROM " + tableName + " WHERE status = ? AND zap_scan_id IS NOT NULL "
                + "AND (claim_owner_id = ? OR claim_owner_id IS NULL OR claim_expires_at <= ?) "
                + "ORDER BY COALESCE(started_at, created_at), job_id FOR UPDATE SKIP LOCKED";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ScanJobStatus.RUNNING.name());
            statement.setString(2, workerId);
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<ScanJob> jobs = new ArrayList<>();
                while (resultSet.next()) {
                    jobs.add(mapRow(resultSet));
                }
                jobs.sort(JOB_ORDER);
                return jobs;
            }
        }
    }

    private List<ScanJob> loadClaimableQueuedJobs(Connection connection, Instant now) throws Exception {
        String sql = "SELECT * FROM " + tableName + " WHERE status = ? "
                + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                + "AND (claim_owner_id IS NULL OR claim_expires_at <= ?) "
                + "ORDER BY CASE WHEN queue_position IS NULL OR queue_position <= 0 THEN 2147483647 ELSE queue_position END, "
                + "created_at, job_id FOR UPDATE SKIP LOCKED";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ScanJobStatus.QUEUED.name());
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                ArrayList<ScanJob> jobs = new ArrayList<>();
                while (resultSet.next()) {
                    jobs.add(mapRow(resultSet));
                }
                jobs.sort(Comparator
                        .comparingInt((ScanJob job) -> job.getQueuePosition() > 0 ? job.getQueuePosition() : Integer.MAX_VALUE)
                        .thenComparing(ScanJob::getCreatedAt)
                        .thenComparing(ScanJob::getId));
                return jobs;
            }
        }
    }

    private int countCapacityInUse(Connection connection, boolean activeFamily, Instant now) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE ("
                + "job_type IN (" + familySqlPlaceholders(activeFamily) + ")"
                + ") AND (status = ? OR (status = ? AND claim_owner_id IS NOT NULL AND claim_expires_at > ?))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindFamilyTypes(statement, 1, activeFamily);
            statement.setString(index++, ScanJobStatus.RUNNING.name());
            statement.setString(index++, ScanJobStatus.QUEUED.name());
            statement.setTimestamp(index, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0;
                }
                return resultSet.getInt(1);
            }
        }
    }

    private boolean hasAjaxCapacityInUse(Connection connection, Instant now) throws Exception {
        String sql = "SELECT 1 FROM " + tableName + " WHERE job_type = ? "
                + "AND (status = ? OR (status = ? AND claim_owner_id IS NOT NULL AND claim_expires_at > ?)) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ScanJobType.AJAX_SPIDER.name());
            statement.setString(2, ScanJobStatus.RUNNING.name());
            statement.setString(3, ScanJobStatus.QUEUED.name());
            statement.setTimestamp(4, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private int bindFamilyTypes(PreparedStatement statement, int startIndex, boolean activeFamily) throws SQLException {
        List<ScanJobType> familyTypes = activeFamily
                ? List.of(ScanJobType.ACTIVE_SCAN, ScanJobType.ACTIVE_SCAN_AS_USER)
                : List.of(ScanJobType.SPIDER_SCAN, ScanJobType.SPIDER_SCAN_AS_USER, ScanJobType.AJAX_SPIDER);
        int index = startIndex;
        for (ScanJobType type : familyTypes) {
            statement.setString(index++, type.name());
        }
        return index;
    }

    private String familySqlPlaceholders(boolean activeFamily) {
        return activeFamily ? "?, ?" : "?, ?, ?";
    }

    private void normalizeQueuePositionTransition(Connection connection, ScanJob job) throws Exception {
        if (job.getStatus() != ScanJobStatus.QUEUED) {
            job.assignQueuePosition(0);
            return;
        }
        if (job.getQueuePosition() <= 0) {
            job.assignQueuePosition(nextQueuePosition(connection));
        }
    }

    private ScanJob mapRow(ResultSet resultSet) throws Exception {
        return ScanJob.restore(
                resultSet.getString("job_id"),
                ScanJobType.valueOf(resultSet.getString("job_type")),
                objectMapper.readValue(resultSet.getString("parameters_json"), STRING_MAP_TYPE),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getInt("max_attempts"),
                resultSet.getString("requester_id"),
                resultSet.getString("idempotency_key"),
                ScanJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                resultSet.getString("zap_scan_id"),
                resultSet.getString("last_error"),
                toInstant(resultSet.getTimestamp("started_at")),
                toInstant(resultSet.getTimestamp("completed_at")),
                toInstant(resultSet.getTimestamp("next_attempt_at")),
                resultSet.getInt("last_known_progress"),
                resultSet.getInt("queue_position"),
                resultSet.getString("claim_owner_id"),
                resultSet.getString("claim_fence_id"),
                toInstant(resultSet.getTimestamp("claim_heartbeat_at")),
                toInstant(resultSet.getTimestamp("claim_expires_at"))
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

    private boolean shouldRetryTransactionalUpdate(Exception error, int attempt) {
        if (attempt >= MAX_TRANSACTION_RETRIES) {
            return false;
        }
        String sqlState = extractSqlState(error);
        return UNIQUE_VIOLATION_SQLSTATE.equals(sqlState)
                || SERIALIZATION_FAILURE_SQLSTATE.equals(sqlState)
                || DEADLOCK_SQLSTATE.equals(sqlState);
    }

    private String extractSqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
