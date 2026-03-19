package mcp.server.zap.core.service.queue.leadership;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.QueueCoordinatorProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class PostgresAdvisoryLockQueueLeadershipCoordinator implements QueueLeadershipCoordinator {

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";
    private static final String HEARTBEAT_SQL = "SELECT 1";

    private final String nodeId;
    private final long advisoryLockKey;
    private final long heartbeatIntervalMs;
    private final boolean failFast;
    private final String url;
    private final String username;
    private final String password;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger leaderGauge = new AtomicInteger(0);
    private final Counter acquiredCounter;
    private final Counter lostCounter;
    private final Counter heartbeatFailureCounter;
    private final Counter acquisitionFailureCounter;

    private Connection leaderConnection;
    private boolean leader;
    private long lastHeartbeatCheckAtMs;

    public PostgresAdvisoryLockQueueLeadershipCoordinator(
            String nodeId,
            QueueCoordinatorProperties.Postgres postgres,
            MeterRegistry meterRegistry
    ) {
        this.nodeId = nodeId;
        this.advisoryLockKey = postgres.getAdvisoryLockKey();
        this.heartbeatIntervalMs = Math.max(1000, postgres.getHeartbeatIntervalMs());
        this.failFast = postgres.isFailFast();
        this.url = postgres.getUrl();
        this.username = postgres.getUsername();
        this.password = postgres.getPassword();

        if (meterRegistry != null) {
            meterRegistry.gauge("asg.queue.leadership.is_leader", leaderGauge);
            this.acquiredCounter = Counter.builder("asg.queue.leadership.transitions")
                    .tag("event", "acquired")
                    .register(meterRegistry);
            this.lostCounter = Counter.builder("asg.queue.leadership.transitions")
                    .tag("event", "lost")
                    .register(meterRegistry);
            this.heartbeatFailureCounter = Counter.builder("asg.queue.leadership.failures")
                    .tag("type", "heartbeat")
                    .register(meterRegistry);
            this.acquisitionFailureCounter = Counter.builder("asg.queue.leadership.failures")
                    .tag("type", "acquire")
                    .register(meterRegistry);
        } else {
            this.acquiredCounter = null;
            this.lostCounter = null;
            this.heartbeatFailureCounter = null;
            this.acquisitionFailureCounter = null;
        }
    }

    /**
     * Evaluate and possibly transition leadership based on advisory lock state.
     */
    @Override
    public LeadershipDecision evaluateLeadership() {
        lock.lock();
        try {
            boolean wasLeader = leader;
            if (leader && !isLeaderConnectionHealthy()) {
                releaseLeadershipInternal("leader heartbeat failed");
                incrementCounter(heartbeatFailureCounter);
            }

            if (!leader) {
                tryAcquireLeadership();
            }

            boolean acquiredLeadership = !wasLeader && leader;
            boolean lostLeadership = wasLeader && !leader;
            return new LeadershipDecision(leader, acquiredLeadership, lostLeadership);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String nodeId() {
        return nodeId;
    }

    /**
     * Release resources and advisory lock on shutdown.
     */
    @Override
    public void close() {
        lock.lock();
        try {
            releaseLeadershipInternal("coordinator closed");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempt to acquire advisory lock and become leader.
     */
    private void tryAcquireLeadership() {
        Connection connection = null;
        try {
            connection = openConnection();
            boolean lockAcquired = tryAdvisoryLock(connection);
            if (!lockAcquired) {
                closeQuietly(connection);
                return;
            }

            leaderConnection = connection;
            leader = true;
            leaderGauge.set(1);
            incrementCounter(acquiredCounter);
            log.info("Queue leadership acquired (nodeId={}, lockKey={})", nodeId, advisoryLockKey);
        } catch (Exception e) {
            incrementCounter(acquisitionFailureCounter);
            closeQuietly(connection);
            if (failFast) {
                throw new IllegalStateException("Failed to acquire queue leadership", e);
            }
            log.warn("Queue leadership acquire failed (nodeId={}): {}", nodeId, e.getMessage());
        }
    }

    /**
     * Release advisory lock and reset local leader state.
     */
    private void releaseLeadershipInternal(String reason) {
        if (leaderConnection != null) {
            try (PreparedStatement statement = leaderConnection.prepareStatement(UNLOCK_SQL)) {
                statement.setLong(1, advisoryLockKey);
                statement.executeQuery();
            } catch (Exception e) {
                if (failFast) {
                    throw new IllegalStateException("Failed to release queue leadership lock", e);
                }
                log.warn("Queue leadership unlock failed (nodeId={}): {}", nodeId, e.getMessage());
            } finally {
                closeQuietly(leaderConnection);
                leaderConnection = null;
            }
        }

        if (leader) {
            leader = false;
            leaderGauge.set(0);
            incrementCounter(lostCounter);
            log.info("Queue leadership released (nodeId={}, reason={})", nodeId, reason);
        }
    }

    /**
     * Execute periodic heartbeat query on leader connection.
     */
    private boolean isLeaderConnectionHealthy() {
        if (leaderConnection == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastHeartbeatCheckAtMs < heartbeatIntervalMs) {
            return true;
        }
        lastHeartbeatCheckAtMs = now;

        try (PreparedStatement statement = leaderConnection.prepareStatement(HEARTBEAT_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (Exception e) {
            log.warn("Queue leadership heartbeat failed (nodeId={}): {}", nodeId, e.getMessage());
            return false;
        }
    }

    private boolean tryAdvisoryLock(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
            statement.setLong(1, advisoryLockKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                return resultSet.getBoolean(1);
            }
        }
    }

    private Connection openConnection() throws Exception {
        if (username == null || username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * Close JDBC connection safely without propagating close failures.
     */
    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // best-effort close
        }
    }

    /**
     * Increment optional metric counter when registered.
     */
    private void incrementCounter(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }
}
