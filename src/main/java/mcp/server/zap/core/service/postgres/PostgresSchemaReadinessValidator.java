package mcp.server.zap.core.service.postgres;

import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.configuration.ScanHistoryLedgerProperties;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

@Component
public class PostgresSchemaReadinessValidator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaReadinessValidator.class);

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final ScanJobStoreProperties scanJobStoreProperties;
    private final ScanHistoryLedgerProperties scanHistoryLedgerProperties;
    private final TokenRevocationStoreProperties tokenRevocationStoreProperties;

    public PostgresSchemaReadinessValidator(
            ScanJobStoreProperties scanJobStoreProperties,
            ScanHistoryLedgerProperties scanHistoryLedgerProperties,
            TokenRevocationStoreProperties tokenRevocationStoreProperties
    ) {
        this.scanJobStoreProperties = scanJobStoreProperties;
        this.scanHistoryLedgerProperties = scanHistoryLedgerProperties;
        this.tokenRevocationStoreProperties = tokenRevocationStoreProperties;
    }

    @Override
    public void afterPropertiesSet() {
        validateScanJobSchema();
        validateScanHistorySchema();
        validateJwtRevocationSchema();
    }

    private void validateScanJobSchema() {
        if (!isPostgresBackend(scanJobStoreProperties.getBackend())) {
            return;
        }

        ScanJobStoreProperties.Postgres postgres = scanJobStoreProperties.getPostgres();
        if (!hasText(postgres.getUrl())) {
            return;
        }

        String tableName = validateTableName(postgres.getTableName(), "scan job");
        validateScanJobTableAccessible(
                postgres.getUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                tableName
        );
    }

    private void validateJwtRevocationSchema() {
        if (!isPostgresBackend(tokenRevocationStoreProperties.getBackend())) {
            return;
        }

        TokenRevocationStoreProperties.Postgres postgres = tokenRevocationStoreProperties.getPostgres();
        if (!hasText(postgres.getUrl())) {
            return;
        }

        String tableName = validateTableName(postgres.getTableName(), "JWT revocation");
        validateTableAccessible(
                postgres.getUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                tableName,
                "JWT revocation"
        );
    }

    private void validateScanHistorySchema() {
        String backend = hasText(scanHistoryLedgerProperties.getBackend())
                ? scanHistoryLedgerProperties.getBackend()
                : scanJobStoreProperties.getBackend();
        if (!isPostgresBackend(backend)) {
            return;
        }

        ScanHistoryLedgerProperties.Postgres postgres = scanHistoryLedgerProperties.getPostgres();
        String url = hasText(postgres.getUrl()) ? postgres.getUrl() : scanJobStoreProperties.getPostgres().getUrl();
        if (!hasText(url)) {
            return;
        }
        String username = hasText(postgres.getUsername())
                ? postgres.getUsername()
                : scanJobStoreProperties.getPostgres().getUsername();
        String password = hasText(postgres.getPassword())
                ? postgres.getPassword()
                : scanJobStoreProperties.getPostgres().getPassword();
        String tableName = validateTableName(postgres.getTableName(), "scan history");
        validateScanHistoryTableAccessible(url, username, password, tableName);
    }

    private void validateTableAccessible(
            String url,
            String username,
            String password,
            String tableName,
            String schemaSubject
    ) {
        String sql = "SELECT 1 FROM " + tableName + " LIMIT 0";
        try (Connection connection = openConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Validated Postgres schema for {} using table '{}'", schemaSubject, tableName);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Required Postgres schema for " + schemaSubject + " is missing or not accessible. "
                            + "Apply Flyway migrations before starting MCP replicas. Expected table '" + tableName + "'.",
                    e
            );
        }
    }

    private void validateScanJobTableAccessible(
            String url,
            String username,
            String password,
            String tableName
    ) {
        String sql = "SELECT job_id, queue_position, requester_id, idempotency_key, "
                + "claim_owner_id, claim_fence_id, claim_heartbeat_at, claim_expires_at "
                + "FROM " + tableName + " LIMIT 0";
        try (Connection connection = openConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Validated Postgres schema for scan job using table '{}'", tableName);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Required Postgres schema for scan job is missing or not accessible. "
                            + "Apply Flyway migrations before starting MCP replicas. Expected table '"
                            + tableName + "' with columns 'queue_position', 'requester_id', 'idempotency_key', "
                            + "'claim_owner_id', 'claim_fence_id', 'claim_heartbeat_at', and 'claim_expires_at'.",
                    e
            );
        }
    }

    private void validateScanHistoryTableAccessible(
            String url,
            String username,
            String password,
            String tableName
    ) {
        String sql = "SELECT ledger_id, recorded_at, evidence_type, operation_kind, status, engine_id, "
                + "target_kind, target_url, workspace_id, metadata_json "
                + "FROM " + tableName + " LIMIT 0";
        try (Connection connection = openConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Validated Postgres schema for scan history using table '{}'", tableName);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Required Postgres schema for scan history is missing or not accessible. "
                            + "Apply Flyway migrations before starting MCP replicas. Expected table '"
                            + tableName + "' with columns 'ledger_id', 'recorded_at', 'evidence_type', "
                            + "'operation_kind', 'status', 'engine_id', 'target_kind', 'target_url', "
                            + "'workspace_id', and 'metadata_json'.",
                    e
            );
        }
    }

    private String validateTableName(String configuredTableName, String schemaSubject) {
        String value = configuredTableName == null ? "" : configuredTableName.trim();
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + schemaSubject + " table-name '" + configuredTableName
                            + "'. If you customize shared Postgres table names, provide matching Flyway migrations."
            );
        }
        return value;
    }

    private Connection openConnection(String url, String username, String password) throws SQLException {
        if (!hasText(username)) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    private boolean isPostgresBackend(String backend) {
        return backend != null && "postgres".equalsIgnoreCase(backend.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
