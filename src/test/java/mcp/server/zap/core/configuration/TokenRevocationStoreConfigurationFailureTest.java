package mcp.server.zap.core.configuration;

import java.time.Instant;
import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import mcp.server.zap.core.service.revocation.PostgresTokenRevocationStore;
import mcp.server.zap.core.service.revocation.TokenRevocationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenRevocationStoreConfigurationFailureTest {
    private final TokenRevocationStoreConfiguration configuration = new TokenRevocationStoreConfiguration();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedPostgresRejectsMissingUrlInsteadOfDowngrading(boolean failFast) {
        for (String url : new String[]{null, "", "   "}) {
            TokenRevocationStoreProperties properties = postgresProperties(failFast);
            properties.getPostgres().setUrl(url);

            assertThrows(IllegalStateException.class, () -> configuration.tokenRevocationStore(properties));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectedPostgresRejectsUnsafeTableInsteadOfDowngrading(boolean failFast) {
        TokenRevocationStoreProperties properties = postgresProperties(failFast);
        properties.getPostgres().setTableName("jwt_token_revocation; DROP TABLE users");

        assertThrows(IllegalArgumentException.class, () -> configuration.tokenRevocationStore(properties));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unknownBackendCannotSilentlySelectInMemory(boolean failFast) {
        TokenRevocationStoreProperties properties = postgresProperties(failFast);
        properties.setBackend("postgre-sql");

        assertThrows(IllegalArgumentException.class, () -> configuration.tokenRevocationStore(properties));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unreachablePostgresRemainsSelectedAndFailsAtUse(boolean failFast) {
        TokenRevocationStore store = configuration.tokenRevocationStore(postgresProperties(failFast));

        assertInstanceOf(PostgresTokenRevocationStore.class, store);
        assertThrows(IllegalStateException.class, () -> store.isRevoked("durable-token"));
    }

    @Test
    void explicitInMemoryRetainsLocalRevocationAndOneTimeConsumption() {
        TokenRevocationStoreProperties properties = postgresProperties(false);
        properties.setBackend("in-memory");
        properties.getPostgres().setTableName("unused invalid postgres table");
        TokenRevocationStore store = configuration.tokenRevocationStore(properties);
        Instant expiry = Instant.now().plusSeconds(3600);

        assertInstanceOf(InMemoryTokenRevocationStore.class, store);
        assertFalse(store.isRevoked("access"));
        store.revoke("access", expiry);
        assertTrue(store.isRevoked("access"));
        assertTrue(store.revokeIfActive("refresh", expiry));
        assertFalse(store.revokeIfActive("refresh", expiry));
    }

    private static TokenRevocationStoreProperties postgresProperties(boolean failFast) {
        TokenRevocationStoreProperties properties = new TokenRevocationStoreProperties();
        properties.setBackend("postgres");
        properties.getPostgres().setUrl(
                "jdbc:postgresql://127.0.0.1:1/revocation_test?connectTimeout=1&socketTimeout=1");
        properties.getPostgres().setFailFast(failFast);
        return properties;
    }
}
