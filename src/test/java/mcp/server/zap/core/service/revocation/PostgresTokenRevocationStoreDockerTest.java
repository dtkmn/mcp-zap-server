package mcp.server.zap.core.service.revocation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mcp.server.zap.core.configuration.TokenRevocationStoreProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("docker")
@Testcontainers
class PostgresTokenRevocationStoreDockerTest {
    private static final String UNAVAILABLE_URL =
            "jdbc:postgresql://127.0.0.1:1/revocation_test?connectTimeout=1&socketTimeout=1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clearRevocations() throws Exception {
        executeSql("TRUNCATE TABLE jwt_token_revocation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void revocationsAndConsumedRefreshIdsSurviveIndependentInstanceFailureAndRecovery(boolean failFast) {
        StoreFixture first = fixture(failFast);
        StoreFixture second = fixture(failFast);
        Instant expiry = Instant.now().plusSeconds(3600);
        first.store().revoke("durable-access", expiry);
        assertTrue(first.store().revokeIfActive("consumed-refresh", expiry));
        assertTrue(second.store().isRevoked("durable-access"));
        assertFalse(second.store().revokeIfActive("consumed-refresh", expiry));

        String healthyUrl = second.properties().getUrl();
        second.properties().setUrl(UNAVAILABLE_URL);
        try {
            assertAll(
                    () -> assertThrows(IllegalStateException.class,
                            () -> second.store().isRevoked("durable-access")),
                    () -> assertThrows(IllegalStateException.class,
                            () -> second.store().isRevoked("consumed-refresh")),
                    () -> assertThrows(IllegalStateException.class,
                            () -> second.store().revokeIfActive("consumed-refresh", expiry)),
                    () -> assertTrue(first.store().isRevoked("durable-access")),
                    () -> assertFalse(first.store().revokeIfActive("consumed-refresh", expiry))
            );
        } finally {
            second.properties().setUrl(healthyUrl);
        }

        assertTrue(second.store().isRevoked("durable-access"));
        assertTrue(second.store().isRevoked("consumed-refresh"));
        assertFalse(second.store().revokeIfActive("consumed-refresh", expiry));
        assertEquals(2, fixture(failFast).store().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectedOutageWritesBecomeDurableOnlyAfterSuccessfulRetry(boolean failFast) {
        StoreFixture writer = fixture(failFast);
        StoreFixture peer = fixture(failFast);
        Instant expiry = Instant.now().plusSeconds(3600);
        String healthyUrl = writer.properties().getUrl();
        writer.properties().setUrl(UNAVAILABLE_URL);
        try {
            assertAll(
                    () -> assertThrows(IllegalStateException.class,
                            () -> writer.store().revoke("retry-access", expiry)),
                    () -> assertThrows(IllegalStateException.class,
                            () -> writer.store().revokeIfActive("retry-refresh", expiry))
            );
        } finally {
            writer.properties().setUrl(healthyUrl);
        }

        // An unacknowledged write never reached shared storage; do not claim global revocation.
        assertFalse(peer.store().isRevoked("retry-access"));
        assertFalse(peer.store().isRevoked("retry-refresh"));
        writer.store().revoke("retry-access", expiry);
        assertTrue(writer.store().revokeIfActive("retry-refresh", expiry));
        assertTrue(peer.store().isRevoked("retry-access"));
        assertTrue(peer.store().isRevoked("retry-refresh"));
        assertFalse(peer.store().revokeIfActive("retry-refresh", expiry));
        assertFalse(writer.store().revokeIfActive("retry-refresh", expiry));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRefreshConsumptionHasExactlyOneWinnerAcrossIndependentInstances(boolean failFast)
            throws Exception {
        List<PostgresTokenRevocationStore> stores = List.of(fixture(failFast).store(), fixture(failFast).store());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 5; round++) {
                String tokenId = "concurrent-refresh-" + round;
                Instant expiry = Instant.now().plusSeconds(3600);
                CyclicBarrier start = new CyclicBarrier(8);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int participant = 0; participant < 8; participant++) {
                    PostgresTokenRevocationStore store = stores.get(participant % stores.size());
                    results.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return store.revokeIfActive(tokenId, expiry);
                    }));
                }
                int successes = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        successes++;
                    }
                }
                assertEquals(1, successes, "Only one instance may consume a refresh ID in round " + round);
                assertTrue(stores.getFirst().isRevoked(tokenId));
                assertFalse(stores.getLast().revokeIfActive(tokenId, expiry));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedMaintenanceCannotMisreportSuccessfullyPersistedRevocation(boolean failFast) throws Exception {
        StoreFixture writer = fixture(failFast);
        StoreFixture peer = fixture(failFast);
        executeSql("""
                CREATE FUNCTION reject_revocation_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic expiration cleanup failure';
                END;
                $$
                """);
        try {
            executeSql("CREATE TRIGGER revocation_cleanup_failure BEFORE DELETE ON jwt_token_revocation "
                    + "FOR EACH STATEMENT EXECUTE FUNCTION reject_revocation_cleanup()");
            assertDoesNotThrow(() -> writer.store().revoke("cleanup-independent-access", Instant.now().plusSeconds(3600)));
            assertTrue(peer.store().isRevoked("cleanup-independent-access"));
            assertEquals(1, peer.store().size());

            if (failFast) {
                assertThrows(IllegalStateException.class, writer.store()::cleanupExpired);
            } else {
                assertDoesNotThrow(writer.store()::cleanupExpired);
            }
            assertTrue(peer.store().isRevoked("cleanup-independent-access"));
        } finally {
            executeSql("DROP TRIGGER IF EXISTS revocation_cleanup_failure ON jwt_token_revocation");
            executeSql("DROP FUNCTION IF EXISTS reject_revocation_cleanup()");
        }
    }

    private static StoreFixture fixture(boolean failFast) {
        TokenRevocationStoreProperties.Postgres properties = new TokenRevocationStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setFailFast(failFast);
        return new StoreFixture(properties, new PostgresTokenRevocationStore(properties));
    }

    private static void executeSql(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record StoreFixture(TokenRevocationStoreProperties.Postgres properties,
                                PostgresTokenRevocationStore store) {}
}
