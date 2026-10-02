package mcp.server.zap.core.service.revocation;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;
import mcp.server.zap.core.service.JwtTokenLifetime;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
public class PostgresTokenRevocationStore implements TokenRevocationStore {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final TokenRevocationStoreProperties.Postgres properties;
    private final String tableName;
    private final Clock clock;

    /**
     * Build Postgres-backed token revocation store with validated table name.
     */
    public PostgresTokenRevocationStore(TokenRevocationStoreProperties.Postgres properties) {
        this(properties, Clock.systemUTC());
    }

    public PostgresTokenRevocationStore(TokenRevocationStoreProperties.Postgres properties, Clock clock) {
        this.properties = properties;
        this.tableName = validateTableName(properties.getTableName());
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
            Instant now = clock.instant();
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new TokenRevocationUnavailableException(e);
        }

        // Housekeeping must not negate an already persisted revocation.
        try {
            cleanupExpired();
        } catch (TokenRevocationUnavailableException e) {
            log.warn("Expired JWT revocation cleanup failed after a successful revocation");
        }
    }

    /**
     * Revoke token only if existing record is missing or expired.
     */
    @Override
    public boolean revokeIfActive(String tokenId, Instant expiresAt) {
        Instant now = clock.instant();
        Instant cutoff = JwtTokenLifetime.revocationCutoff(now);
        if (expiresAt.isBefore(cutoff)) {
            return false;
        }
        try {
            String sql = "INSERT INTO " + tableName
                    + " (token_id, expires_at, revoked_at) VALUES (?, ?, ?) "
                    + "ON CONFLICT (token_id) DO UPDATE SET "
                    + "expires_at = EXCLUDED.expires_at, "
                    + "revoked_at = EXCLUDED.revoked_at "
                    + "WHERE " + tableName + ".expires_at < ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                statement.setTimestamp(3, Timestamp.from(now));
                statement.setTimestamp(4, Timestamp.from(cutoff));
                int affectedRows = statement.executeUpdate();
                // A successful write must not revive a token that expired while the query waited.
                return affectedRows > 0
                        && !expiresAt.isBefore(JwtTokenLifetime.revocationCutoff(clock.instant()));
            }
        } catch (SQLException e) {
            throw new TokenRevocationUnavailableException(e);
        }
    }

    /**
     * Return true while the JWT validator can still accept the recorded token.
     */
    @Override
    public boolean isRevoked(String tokenId) {
        try {
            String sql = "SELECT 1 FROM " + tableName + " WHERE token_id = ? AND expires_at >= ? LIMIT 1";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenId);
                statement.setTimestamp(2, Timestamp.from(JwtTokenLifetime.revocationCutoff(clock.instant())));
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        } catch (SQLException e) {
            throw new TokenRevocationUnavailableException(e);
        }
    }

    /**
     * Delete expired revocation records.
     */
    @Override
    public void cleanupExpired() {
        try {
            String sql = "DELETE FROM " + tableName + " WHERE expires_at < ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(JwtTokenLifetime.revocationCutoff(clock.instant())));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            if (properties.isFailFast()) {
                throw new TokenRevocationUnavailableException(e);
            }
            log.warn("Expired JWT revocation cleanup failed");
        }
    }

    /**
     * Return count of currently active revocation records.
     */
    @Override
    public int size() {
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE expires_at >= ?";
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(JwtTokenLifetime.revocationCutoff(clock.instant())));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                    return 0;
                }
            }
        } catch (SQLException e) {
            throw new TokenRevocationUnavailableException(e);
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
        } catch (SQLException e) {
            throw new TokenRevocationUnavailableException(e);
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
}
