package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * MCP-facing tools for passive scan backlog visibility and completion waits.
 */
@Slf4j
@Service
public class PassiveScanService {
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_WAIT_POLL_INTERVAL_MS = 1000;

    private final ClientApi zap;

    public PassiveScanService(ClientApi zap) {
        this.zap = zap;
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
        try {
            int recordsToScan = readIntElement(zap.pscan.recordsToScan(), "recordsToScan");
            boolean scanOnlyInScope = Boolean.parseBoolean(readElementValue(zap.pscan.scanOnlyInScope(), "scanOnlyInScope"));
            int activeTasks = readCurrentTaskCount();
            return new PassiveScanSnapshot(recordsToScan, activeTasks, scanOnlyInScope, recordsToScan == 0);
        } catch (ClientApiException e) {
            log.error("Error retrieving passive scan status: {}", e.getMessage(), e);
            throw new ZapApiException("Error retrieving passive scan status", e);
        }
    }

    private int readCurrentTaskCount() throws ClientApiException {
        try {
            ApiResponse response = zap.pscan.currentTasks();
            if (response instanceof ApiResponseList list) {
                return list.getItems().size();
            }
            if (response instanceof ApiResponseSet set) {
                return set.getValues().size();
            }
            if (response instanceof ApiResponseElement element) {
                return Integer.parseInt(element.getValue());
            }
            return -1;
        } catch (NumberFormatException e) {
            log.debug("Unable to parse passive scan currentTasks response; reporting as unknown", e);
            return -1;
        } catch (ClientApiException e) {
            log.debug("Passive scan currentTasks view unavailable; reporting active task count as unknown: {}", e.getMessage());
            return -1;
        }
    }

    private int readIntElement(ApiResponse response, String operationName) {
        try {
            return Integer.parseInt(readElementValue(response, operationName));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Unexpected numeric response from pscan." + operationName, e);
        }
    }

    private String readElementValue(ApiResponse response, String operationName) {
        if (!(response instanceof ApiResponseElement element)) {
            throw new IllegalStateException("Unexpected response from pscan." + operationName + "(): " + response);
        }
        return element.getValue();
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

    private record PassiveScanSnapshot(int recordsToScan, int activeTasks, boolean scanOnlyInScope, boolean completed) {
    }
}
