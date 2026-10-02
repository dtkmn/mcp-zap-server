package mcp.server.zap.core.service.revocation;

import java.time.Instant;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTokenRevocationStoreFailureTest {
    private static final Instant EXPIRY = Instant.now().plusSeconds(3600);

    @Test
    void postgresDefaultsToFailFast() {
        assertTrue(new TokenRevocationStoreProperties.Postgres().isFailFast());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableLookupCannotReportTokenAsActive(boolean failFast) {
        PostgresTokenRevocationStore store = unavailableStore(failFast);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.isRevoked("previously-revoked-on-another-instance"));

        assertNotNull(failure.getCause(), "The backend failure must remain diagnosable internally");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableWritesCannotAcknowledgeRevocationOrRefreshConsumption(boolean failFast) {
        PostgresTokenRevocationStore store = unavailableStore(failFast);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> store.revoke("access-token", EXPIRY)),
                () -> assertThrows(IllegalStateException.class,
                        () -> store.revokeIfActive("refresh-token", EXPIRY)),
                () -> assertThrows(IllegalStateException.class,
                        () -> store.revokeIfActive("refresh-token", EXPIRY)),
                () -> assertThrows(IllegalStateException.class, () -> store.isRevoked("access-token"))
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unavailableCountAndClearCannotReturnLocalFallbackResults(boolean failFast) {
        PostgresTokenRevocationStore store = unavailableStore(failFast);

        assertAll(
                () -> assertThrows(IllegalStateException.class, store::size),
                () -> assertThrows(IllegalStateException.class, store::clear)
        );
    }

    @Test
    void failFastFalseOnlyAllowsBestEffortExpirationCleanup() {
        assertDoesNotThrow(() -> unavailableStore(false).cleanupExpired());
        assertThrows(IllegalStateException.class, () -> unavailableStore(true).cleanupExpired());
    }

    private static PostgresTokenRevocationStore unavailableStore(boolean failFast) {
        TokenRevocationStoreProperties.Postgres properties = new TokenRevocationStoreProperties.Postgres();
        properties.setUrl("jdbc:postgresql://127.0.0.1:1/revocation_test?connectTimeout=1&socketTimeout=1");
        properties.setUsername("synthetic-revocation-user");
        properties.setPassword("synthetic-revocation-password");
        properties.setFailFast(failFast);
        return new PostgresTokenRevocationStore(properties);
    }
}
