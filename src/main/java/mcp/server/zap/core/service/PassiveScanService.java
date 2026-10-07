package mcp.server.zap.core.service;

import jakarta.annotation.PreDestroy;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP-facing tools for passive scan backlog visibility and completion waits.
 */
@Service
public class PassiveScanService {
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_WAIT_POLL_INTERVAL_MS = 1000;
    private static final int MAX_WAIT_TIMEOUT_SECONDS = 300;
    private static final int MAX_WAIT_POLL_INTERVAL_MS = 10_000;

    private final EnginePassiveScanAccess passiveScanAccess;
    // No pending wait queue; slow or non-interruptible engine calls retain one of four slots.
    private final ThreadPoolExecutor waitExecutor = new ThreadPoolExecutor(
            0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().daemon(true).name("zap-passive-wait-", 0).factory());

    public PassiveScanService(EnginePassiveScanAccess passiveScanAccess) {
        this.passiveScanAccess = passiveScanAccess;
    }

    public String getPassiveScanStatus() {
        PassiveScanSnapshot snapshot = readPassiveScanSnapshot();
        return String.format(
                "Passive scan status:%n" +
                        "Completed: %s%n" +
                        "Records remaining: %d%n" +
                        "Active tasks: %s%n" +
                        "Scan only in scope: %s%n" +
                        "%s",
                yesNo(snapshot.completed()),
                snapshot.recordsToScan(),
                formatActiveTasks(snapshot.activeTasks()),
                snapshot.scanOnlyInScope(),
                snapshot.completed()
                        ? "Passive analysis backlog is drained. It is safe to read findings or generate a report."
                        : "Passive analysis is still running. Use 'zap_passive_scan_wait' after spider, AJAX spider, or active scan flows when you need bounded completion."
        );
    }

    public String waitForPassiveScanCompletion(Integer timeoutSeconds, Integer pollIntervalMs) {
        int effectiveTimeoutSeconds = boundedOrDefault(timeoutSeconds, DEFAULT_WAIT_TIMEOUT_SECONDS,
                MAX_WAIT_TIMEOUT_SECONDS, "timeoutSeconds");
        int effectivePollIntervalMs = boundedOrDefault(pollIntervalMs, DEFAULT_WAIT_POLL_INTERVAL_MS,
                MAX_WAIT_POLL_INTERVAL_MS, "pollIntervalMs");

        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + TimeUnit.SECONDS.toNanos(effectiveTimeoutSeconds);
        AtomicReference<PassiveScanSnapshot> latestSnapshot = new AtomicReference<>();
        Future<String> wait;
        try {
            wait = waitExecutor.submit(() -> waitUntilDeadline(
                    startedAtNanos, deadlineNanos, effectivePollIntervalMs, latestSnapshot));
        } catch (RejectedExecutionException e) {
            throw new IllegalStateException("Passive scan wait capacity is busy; retry after current waits finish", e);
        }
        try {
            return wait.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return formatTimeout(latestSnapshot.get(), elapsedMillis(startedAtNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZapApiException("Passive scan wait was interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new ZapApiException("Passive scan wait failed", e.getCause());
        } finally {
            if (!wait.isDone()) {
                wait.cancel(true);
            }
        }
    }

    private String waitUntilDeadline(long startedAtNanos, long deadlineNanos, int pollIntervalMs,
                                     AtomicReference<PassiveScanSnapshot> latestSnapshot) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ZapApiException("Passive scan wait was interrupted", new InterruptedException());
        }
        if (System.nanoTime() >= deadlineNanos) {
            return formatTimeout(null, elapsedMillis(startedAtNanos));
        }
        PassiveScanSnapshot snapshot = readPassiveScanSnapshot();
        latestSnapshot.set(snapshot);
        while (!snapshot.completed()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(pollIntervalMs), remainingNanos));
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            snapshot = readPassiveScanSnapshot();
            latestSnapshot.set(snapshot);
        }

        long elapsedMillis = elapsedMillis(startedAtNanos);
        if (!snapshot.completed() || System.nanoTime() >= deadlineNanos) {
            return formatTimeout(snapshot, elapsedMillis);
        }

        return String.format(
                "Passive scan backlog drained.%n" +
                        "Completed: yes%n" +
                        "Records remaining: %d%n" +
                        "Active tasks: %s%n" +
                        "Scan only in scope: %s%n" +
                        "Waited: %d ms%n" +
                        "It is now safe to review findings or generate a report.",
                snapshot.recordsToScan(),
                formatActiveTasks(snapshot.activeTasks()),
                snapshot.scanOnlyInScope(),
                elapsedMillis
        );
    }

    private String formatTimeout(PassiveScanSnapshot snapshot, long elapsedMillis) {
        return String.format(
                "Passive scan wait timed out after %d ms.%n" +
                        "Completed: %s%n" +
                        "Records remaining: %s%n" +
                        "Active tasks: %s%n" +
                        "Scan only in scope: %s%n" +
                        "Use 'zap_passive_scan_status' to inspect backlog, then retry 'zap_passive_scan_wait' if you need completion before reading findings.",
                elapsedMillis,
                snapshot == null ? "unknown" : yesNo(snapshot.completed()),
                snapshot == null ? "unknown" : Integer.toString(snapshot.recordsToScan()),
                snapshot == null ? "unknown" : formatActiveTasks(snapshot.activeTasks()),
                snapshot == null ? "unknown" : Boolean.toString(snapshot.scanOnlyInScope()));
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    @PreDestroy
    void shutdownWaits() {
        waitExecutor.shutdownNow();
    }

    private PassiveScanSnapshot readPassiveScanSnapshot() {
        return passiveScanAccess.loadPassiveScanSnapshot();
    }

    private int boundedOrDefault(Integer value, int defaultValue, int maximum, String fieldName) {
        int effectiveValue = value == null ? defaultValue : value;
        if (effectiveValue <= 0 || effectiveValue > maximum) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and " + maximum);
        }
        return effectiveValue;
    }

    private void sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ZapApiException("Passive scan wait was interrupted", e);
        }
    }

    private String formatActiveTasks(int activeTasks) {
        return activeTasks >= 0 ? Integer.toString(activeTasks) : "unknown";
    }

    private String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

}
