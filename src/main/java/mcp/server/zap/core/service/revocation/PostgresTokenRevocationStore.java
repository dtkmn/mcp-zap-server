package mcp.server.zap.core.service.revocation;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;

import java.sql.*;
import java.time.Instant;
import java.util.regex.Pattern;

@Slf4j
public class PostgresTokenRevocationStore implements TokenRevocationStore {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final TokenRevocationStoreProperties.Postgres properties;
    private final InMemoryTokenRevocationStore fallbackStore = new InMemoryTokenRevocationStore();
    private final String tableName;

    /**
     * Build Postgres-backed token revocation store with validated table name.
     */
    public PostgresTokenRevocationStore(TokenRevocationStoreProperties.Postgres properties) {
        this.properties = properties;
        this.tableName = validateTableName(properties.getTableName());
    }

    /**
     * Persist token revocation with upsert semantics.
     */
    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        try {
            String sql = "INSERT INTO " + tableName
                    + " (token_id, expires_at, revoked_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT (token_id) DO UPDATE SET expires_at = EXCLUDED.expires_at, "
                    + "revoked_at = EXCLUDED.revoked_at";
            Instant now = Instant.now();
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.executeUpdate();
            }

            // Keep table bounded during normal revoke activity.
            cleanupExpired();
        } catch (Exception e) {
            handleFailure("token revoke", e);
            fallbackStore.revoke(tokenId, expiresAt);
        }
    }

    /**
     * Revoke token only if existing record is missing or expired.
     */
    @Override
    public boolean revokeIfActive(String tokenId, Instant expiresAt) {
        try {
            String sql = "INSERT INTO " + tableName
                    + " (token_id, expires_at, revoked_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT (token_id) DO UPDATE SET "
                    + "expires_at = EXCLUDED.expires_at, "
                    + "revoked_at = EXCLUDED.revoked_at "
                    + "WHERE " + tableName + ".expires_at <= ?";
            Instant now = Instant.now();
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setTimestamp(4, Timestamp.from(now));
                int affectedRows = statement.executeUpdate();
                return affectedRows > 0;
            }
        } catch (Exception e) {
            handleFailure("conditional token revoke", e);
            return fallbackStore.revokeIfActive(tokenId, expiresAt);
        }
    }

    /**
     * Return true when token has an unexpired revocation record.
     */
    @Override
    public boolean isRevoked(String tokenId) {
        try {
            String sql = "SELECT 1 FROM " + tableName + " WHERE token_id = ? AND expires_at > ? LIMIT 1";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(Instant.now()));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (Exception e) {
            handleFailure("token revocation lookup", e);
            return fallbackStore.isRevoked(tokenId);
        }
    }

    /**
     * Delete expired revocation records.
     */
    @Override
    public void cleanupExpired() {
        try {
            String sql = "DELETE FROM " + tableName + " WHERE expires_at <= ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
        } catch (Exception e) {
            handleFailure("expired token cleanup", e);
            fallbackStore.cleanupExpired();
        }
    }

    /**
     * Return count of currently active revocation records.
     */
    @Override
    public int size() {
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE expires_at > ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(Instant.now()));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                    return 0;
                }
            }
        } catch (Exception e) {
            handleFailure("token revocation count", e);
            return fallbackStore.size();
        }
    }

    /**
     * Clear all persisted revocation records.
     */
    @Override
    public void clear() {
        try {
            String sql = "DELETE FROM " + tableName;
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        } catch (Exception e) {
            handleFailure("token revocation clear", e);
            fallbackStore.clear();
        }
    }

    /**
     * Open JDBC connection using configured credentials.
     */
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

    /**
     * Validate SQL identifier safety for configured table name.
     */
    private String validateTableName(String configuredTableName) {
        String value = configuredTableName == null ? "" : configuredTableName.trim();
        if (!SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid JWT revocation table-name '" + configuredTableName
                            + "'. Allowed pattern: [A-Za-z_][A-Za-z0-9_]*"
            );
        }
        return value;
    }

    /**
     * Apply fail-fast or warning fallback policy on backend failures.
     */
    private void handleFailure(String operation, Exception e) {
        if (properties.isFailFast()) {
            throw new IllegalStateException("Postgres JWT revocation " + operation + " failed", e);
        }
        log.warn("Postgres JWT revocation {} failed (using in-memory fallback): {}", operation, e.getMessage());
    }
}
