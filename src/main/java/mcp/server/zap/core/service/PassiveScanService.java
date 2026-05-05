package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import org.springframework.stereotype.Service;

/**
 * MCP-facing tools for passive scan backlog visibility and completion waits.
 */
@Service
public class PassiveScanService {
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_WAIT_POLL_INTERVAL_MS = 1000;

    private final EnginePassiveScanAccess passiveScanAccess;

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
        int effectiveTimeoutSeconds = positiveOrDefault(timeoutSeconds, DEFAULT_WAIT_TIMEOUT_SECONDS, "timeoutSeconds");
        int effectivePollIntervalMs = positiveOrDefault(pollIntervalMs, DEFAULT_WAIT_POLL_INTERVAL_MS, "pollIntervalMs");

        long startedAtNanos = System.nanoTime();
        long deadlineNanos = startedAtNanos + (effectiveTimeoutSeconds * 1_000_000_000L);

        PassiveScanSnapshot snapshot = readPassiveScanSnapshot();
        while (!snapshot.completed() && System.nanoTime() < deadlineNanos) {
            sleep(effectivePollIntervalMs);
            snapshot = readPassiveScanSnapshot();
        }

        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        boolean timedOut = !snapshot.completed();

        if (timedOut) {
            return String.format(
                    "Passive scan wait timed out after %d ms.%n" +
                            "Completed: no%n" +
                            "Records remaining: %d%n" +
                            "Active tasks: %s%n" +
                            "Scan only in scope: %s%n" +
                            "Use 'zap_passive_scan_status' to inspect backlog, then retry 'zap_passive_scan_wait' if you need completion before reading findings.",
                    elapsedMillis,
                    snapshot.recordsToScan(),
                    formatActiveTasks(snapshot.activeTasks()),
                    snapshot.scanOnlyInScope()
            );
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

    private PassiveScanSnapshot readPassiveScanSnapshot() {
        return passiveScanAccess.loadPassiveScanSnapshot();
    }

    private int positiveOrDefault(Integer value, int defaultValue, String fieldName) {
        int effectiveValue = value == null ? defaultValue : value;
        if (effectiveValue <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return effectiveValue;
    }

    private void sleep(int pollIntervalMs) {
        try {
            Thread.sleep(pollIntervalMs);
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
