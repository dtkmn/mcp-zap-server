package mcp.server.zap.core.service.revocation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
class PostgresTokenRevocationLifetimeDockerTest {
    private static final Instant EXPIRY = Instant.parse("2030-01-01T00:05:00Z");
    // PostgreSQL timestamps have microsecond precision; one nanosecond can round back to the boundary.
    private static final Instant AFTER_DEADLINE = EXPIRY.plusSeconds(60).plusNanos(1_000);
    private static final String UNAVAILABLE_URL =
            "jdbc:postgresql://127.0.0.1:1/revocation_test?connectTimeout=1&socketTimeout=1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void clearRevocations() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE jwt_token_revocation");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyRawExpiryRowsProtectIndependentInstancesUntilInclusiveDeadline(boolean failFast) throws Exception {
        insertLegacyRevocation("legacy-access", EXPIRY);
        insertLegacyRevocation("legacy-refresh", EXPIRY);
        MutableClock clock = new MutableClock(EXPIRY.minusSeconds(1));
        PostgresTokenRevocationStore first = fixture(failFast, clock).store();
        PostgresTokenRevocationStore second = fixture(failFast, clock).store();

        for (Instant now : List.of(EXPIRY.minusSeconds(1), EXPIRY, EXPIRY.plusSeconds(59), EXPIRY.plusSeconds(60))) {
            clock.set(now);
            for (PostgresTokenRevocationStore store : List.of(first, second)) {
                assertTrue(store.isRevoked("legacy-access"), "Legacy access must remain revoked at " + now);
                assertTrue(store.isRevoked("legacy-refresh"), "Legacy refresh must remain consumed at " + now);
                assertFalse(store.revokeIfActive("legacy-refresh", EXPIRY));
                assertFalse(store.revokeIfActive("legacy-refresh", EXPIRY.plusSeconds(300)));
                assertEquals(2, store.size());
                store.cleanupExpired();
            }
            assertEquals(2, physicalRowCount());
            assertEquals(EXPIRY, storedExpiry("legacy-access"));
            assertEquals(EXPIRY, storedExpiry("legacy-refresh"));
        }

        clock.set(AFTER_DEADLINE);
        assertFalse(first.isRevoked("legacy-access"));
        assertFalse(second.isRevoked("legacy-refresh"));
        assertEquals(0, first.size());
        assertEquals(0, second.size());
        second.cleanupExpired();
        assertEquals(0, physicalRowCount(), "Explicit cleanup removes raw-expiry rows after the grace horizon");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newRevocationAndFirstRefreshConsumptionInGraceStoreRawExpiryOnce(boolean failFast) throws Exception {
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(30));
        PostgresTokenRevocationStore writer = fixture(failFast, clock).store();
        PostgresTokenRevocationStore peer = fixture(failFast, clock).store();

        writer.revoke("grace-access", EXPIRY);
        assertTrue(writer.revokeIfActive("grace-refresh", EXPIRY));
        assertEquals(EXPIRY, storedExpiry("grace-access"));
        assertEquals(EXPIRY, storedExpiry("grace-refresh"));
        assertTrue(peer.isRevoked("grace-access"));
        assertFalse(peer.revokeIfActive("grace-refresh", EXPIRY));
        assertEquals(2, peer.size());

        clock.set(EXPIRY.plusSeconds(60));
        writer.cleanupExpired();
        assertEquals(2, physicalRowCount());
        assertTrue(peer.isRevoked("grace-access"));
        assertFalse(peer.revokeIfActive("grace-refresh", EXPIRY));
        assertEquals(2, peer.size());

        clock.set(AFTER_DEADLINE);
        assertEquals(0, peer.size(), "Storing an already grace-adjusted expiry would extend this deadline twice");
        peer.cleanupExpired();
        assertEquals(0, physicalRowCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiredStoredIdIsReusableOnlyAfterGraceWithAnActiveNewExpiry(boolean failFast) throws Exception {
        insertLegacyRevocation("reused-id", EXPIRY);
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(60));
        PostgresTokenRevocationStore first = fixture(failFast, clock).store();
        PostgresTokenRevocationStore second = fixture(failFast, clock).store();
        Instant newExpiry = EXPIRY.plusSeconds(300);

        assertFalse(first.revokeIfActive("reused-id", newExpiry));
        assertEquals(EXPIRY, storedExpiry("reused-id"));
        clock.set(AFTER_DEADLINE);
        assertFalse(first.revokeIfActive("reused-id", EXPIRY));
        assertFalse(second.revokeIfActive("new-expired-id", EXPIRY));
        assertEquals(1, physicalRowCount(), "An expired input must neither insert nor rewrite a row");
        assertEquals(EXPIRY, storedExpiry("reused-id"));

        assertTrue(second.revokeIfActive("reused-id", newExpiry));
        assertEquals(newExpiry, storedExpiry("reused-id"));
        assertTrue(first.isRevoked("reused-id"));
        assertFalse(first.revokeIfActive("reused-id", newExpiry));
        assertEquals(1, first.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consumedRefreshCannotSucceedWhenPeerCleanupCrossesDeadlineBeforeInsert(boolean failFast) throws Exception {
        String consumedId = "deadline-crossing-consumed-refresh";
        insertLegacyRevocation(consumedId, EXPIRY);
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(60));
        PostgresTokenRevocationStore writer = fixture(failFast, clock).store();
        PostgresTokenRevocationStore peer = fixture(failFast, clock).store();

        assertTrue(writer.revokeIfActive("fresh-at-exact-deadline", EXPIRY));
        assertTrue(peer.isRevoked("fresh-at-exact-deadline"));
        assertTrue(peer.isRevoked(consumedId));
        assertEquals(2, physicalRowCount());
        clock.onNextRead(() -> {
            // The writer keeps the inclusive deadline sample while the peer observes the later instant.
            clock.set(AFTER_DEADLINE);
            peer.cleanupExpired();
            assertDoesNotThrow(() -> assertEquals(0, physicalRowCount()));
        });

        assertFalse(writer.revokeIfActive(consumedId, EXPIRY),
                "A stale cutoff must not acknowledge consuming an already-expired refresh token");
        assertEquals(AFTER_DEADLINE, clock.instant());
        assertFalse(writer.isRevoked(consumedId));
        assertFalse(peer.isRevoked(consumedId));
        assertEquals(0, writer.size());
        assertEquals(0, peer.size());
        peer.cleanupExpired();
        assertEquals(0, physicalRowCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentFirstRefreshConsumptionInGraceHasOneWinnerAcrossInstances(boolean failFast) throws Exception {
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(30));
        List<PostgresTokenRevocationStore> stores = List.of(fixture(failFast, clock).store(), fixture(failFast, clock).store());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (long secondsAfterExpiry : List.of(30L, 60L)) {
                clock.set(EXPIRY.plusSeconds(secondsAfterExpiry));
                String tokenId = "concurrent-grace-" + secondsAfterExpiry;
                CyclicBarrier start = new CyclicBarrier(8);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int participant = 0; participant < 8; participant++) {
                    PostgresTokenRevocationStore store = stores.get(participant % stores.size());
                    results.add(executor.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return store.revokeIfActive(tokenId, EXPIRY);
                    }));
                }
                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertEquals(1, winners, "One winner at exp + " + secondsAfterExpiry + " seconds");
                assertEquals(EXPIRY, storedExpiry(tokenId));
                assertTrue(stores.getFirst().isRevoked(tokenId));
                assertFalse(stores.getLast().revokeIfActive(tokenId, EXPIRY));
            }
            assertEquals(2, stores.getFirst().size());
            clock.set(AFTER_DEADLINE);
            assertFalse(stores.getLast().revokeIfActive("concurrent-grace-60", EXPIRY));
            stores.getFirst().cleanupExpired();
            assertEquals(0, physicalRowCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void backendOutageAndRecoveryNearDeadlineCannotEraseRevocationOrAllowReplay(boolean failFast) throws Exception {
        insertLegacyRevocation("outage-access", EXPIRY);
        insertLegacyRevocation("outage-refresh", EXPIRY);
        MutableClock clock = new MutableClock(EXPIRY.plusSeconds(59));
        StoreFixture recovering = fixture(failFast, clock);
        PostgresTokenRevocationStore healthy = fixture(failFast, clock).store();
        String healthyUrl = recovering.properties().getUrl();
        recovering.properties().setUrl(UNAVAILABLE_URL);
        try {
            assertAll(
                    () -> assertThrows(TokenRevocationUnavailableException.class,
                            () -> recovering.store().isRevoked("outage-access")),
                    () -> assertThrows(TokenRevocationUnavailableException.class,
                            () -> recovering.store().isRevoked("outage-refresh")),
                    () -> assertThrows(TokenRevocationUnavailableException.class, recovering.store()::size),
                    () -> assertThrows(TokenRevocationUnavailableException.class,
                            () -> recovering.store().revokeIfActive("outage-refresh", EXPIRY)),
                    () -> assertThrows(TokenRevocationUnavailableException.class,
                            () -> recovering.store().revokeIfActive("first-use-during-outage", EXPIRY)),
                    () -> assertThrows(TokenRevocationUnavailableException.class,
                            () -> recovering.store().revoke("new-access-during-outage", EXPIRY))
            );
            if (failFast) {
                assertThrows(TokenRevocationUnavailableException.class, recovering.store()::cleanupExpired);
            } else {
                assertDoesNotThrow(recovering.store()::cleanupExpired);
            }
            clock.set(EXPIRY.plusSeconds(60));
            assertTrue(healthy.isRevoked("outage-access"));
            assertFalse(healthy.revokeIfActive("outage-refresh", EXPIRY));
            healthy.cleanupExpired();
            assertEquals(2, healthy.size());
            assertEquals(2, physicalRowCount());
        } finally {
            recovering.properties().setUrl(healthyUrl);
        }

        assertTrue(recovering.store().isRevoked("outage-access"));
        assertFalse(recovering.store().revokeIfActive("outage-refresh", EXPIRY));
        assertFalse(healthy.isRevoked("first-use-during-outage"));
        assertFalse(healthy.isRevoked("new-access-during-outage"));
        assertTrue(recovering.store().revokeIfActive("first-use-during-outage", EXPIRY));
        assertFalse(healthy.revokeIfActive("first-use-during-outage", EXPIRY));
        assertEquals(EXPIRY, storedExpiry("first-use-during-outage"));
        assertEquals(3, recovering.store().size());
        clock.set(AFTER_DEADLINE);
        assertEquals(0, recovering.store().size());
        assertFalse(recovering.store().revokeIfActive("delayed-first-use", EXPIRY));
        recovering.store().cleanupExpired();
        assertEquals(0, physicalRowCount());
    }

    private static StoreFixture fixture(boolean failFast, Clock clock) {
        TokenRevocationStoreProperties.Postgres properties = new TokenRevocationStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setFailFast(failFast);
        return new StoreFixture(properties, new PostgresTokenRevocationStore(properties, clock));
    }

    private static void insertLegacyRevocation(String tokenId, Instant expiry) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO jwt_token_revocation (token_id, expires_at, revoked_at) VALUES (?, ?, ?)")) {
            statement.setString(1, tokenId);
            statement.setTimestamp(2, Timestamp.from(expiry));
            statement.setTimestamp(3, Timestamp.from(EXPIRY.minusSeconds(300)));
            statement.executeUpdate();
        }
    }

    private static Instant storedExpiry(String tokenId) throws Exception {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at FROM jwt_token_revocation WHERE token_id = ?")) {
            statement.setString(1, tokenId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "Expected persisted row for " + tokenId);
                return result.getTimestamp(1).toInstant();
            }
        }
    }

    private static int physicalRowCount() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM jwt_token_revocation")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private record StoreFixture(TokenRevocationStoreProperties.Postgres properties,
                                PostgresTokenRevocationStore store) {}

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private final AtomicReference<Runnable> nextReadHook = new AtomicReference<>();

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant now) {
            this.now = now;
        }

        private void onNextRead(Runnable hook) {
            nextReadHook.set(hook);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            Instant sampled = now;
            Runnable hook = nextReadHook.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return sampled;
        }
    }
}
