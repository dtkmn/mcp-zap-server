package mcp.server.zap.core.gateway;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * One controller's transport boundary: maintenance excludes every engine action, including queue,
 * import and authentication traffic. This is deliberately not a distributed engine lock.
 */
@Component
public class EngineMaintenanceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AddonMaintenanceProperties properties;
    private final Path directory;
    private final Map<String, String> activities = new LinkedHashMap<>();
    private volatile boolean quarantined;
    private volatile boolean updating;

    public EngineMaintenanceGate(AddonMaintenanceProperties properties) {
        this.properties = properties;
        String configuredDirectory = properties.getStateDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank() || !Path.of(configuredDirectory).isAbsolute()) {
            throw new IllegalArgumentException("zap.addons.state-directory must be an absolute private durable directory.");
        }
        directory = Path.of(configuredDirectory).normalize();
        restoreRecordedState();
    }

    public boolean isQuarantined() { return quarantined; }
    public boolean isUpdating() { return updating; }
    public boolean isMaintenanceOwner() { return lock.isWriteLockedByCurrentThread(); }

    /** Runs synchronously so maintenance ownership and release stay on the caller's thread. */
    public <T> T withMaintenance(Supplier<T> operation) {
        if (!lock.writeLock().tryLock()) throw new IllegalStateException("Engine actions are in progress; retry after they finish.");
        try {
            if (quarantined || updating) {
                throw new IllegalStateException("Engine maintenance is unresolved; an operator must reconcile the engine before further work.");
            }
            updating = true;
            try {
                return operation.get();
            } finally {
                updating = false;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Marker is durable before sending the install request. A process crash must not resume scans. */
    public void markUpdatePending(String addonId, String candidateVersion) {
        requireMaintenanceOwner();
        persist("update.pending", addonId + ":" + candidateVersion + "\n");
        quarantined = true;
    }

    public void completeVerifiedUpdate() {
        requireMaintenanceOwner();
        try {
            Files.delete(directory.resolve("update.pending"));
            quarantined = false;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clear the maintenance marker; engine remains quarantined.");
        }
    }

    public synchronized Map<String, String> trackedActivities() { return Map.copyOf(activities); }

    public synchronized void completedActivity(String activity) {
        activities.remove(activity);
        persistActivities();
    }

    @FunctionalInterface
    public interface ApiCall<T> { T call() throws ClientApiException; }

    public <T> T apiCall(String component, String type, String method, ApiCall<T> call) throws ClientApiException {
        return apiCall(component, type, method, Map.of(), call);
    }

    public <T> T apiCall(String component, String type, String method, Map<String, String> params, ApiCall<T> call)
            throws ClientApiException {
        if ("view".equals(type)) {
            T result = call.call();
            observeCompletion(component, method, params, result);
            return result;
        }
        if (!properties.isUpdatesEnabled() && !quarantined) return call.call();
        boolean maintenanceOwner = lock.isWriteLockedByCurrentThread();
        if (!lock.readLock().tryLock()) {
            // No request has been dispatched. Preserve the queue's busy deferral contract.
            throw new EngineBusyException("Engine maintenance in progress; engine actions are paused.", null);
        }
        String pending = null;
        try {
            if (quarantined && !maintenanceOwner) throw new ClientApiException("Engine maintenance is unresolved; engine actions are quarantined.");
            if ("autoupdate".equals(component) && !maintenanceOwner) throw new ClientApiException("Add-on mutations require the maintenance workflow.");
            boolean tracked = "action".equals(type)
                    && (("automation".equals(component) && "runPlan".equals(method))
                    || ("clientSpider".equals(component) && "scan".equals(method)));
            // A synchronous import/auth/access request can keep producing traffic after a client
            // timeout, and has no activity enumeration API. Persist uncertainty before dispatch
            // for every action, including configuration actions whose failure may be ambiguous.
            if (!maintenanceOwner) {
                pending = component + ":pending-" + UUID.randomUUID();
                recordPendingAction(pending, component);
            }
            T result = call.call();
            recordSuccessfulAction(pending, component, tracked, result);
            return result;
        } catch (ClientApiException | RuntimeException e) {
            recordFailedAction(pending, component, type, method, e);
            throw e;
        } finally {
            lock.readLock().unlock();
        }
    }

    private synchronized void recordPendingAction(String pending, String component) throws ClientApiException {
        if (activities.size() >= 1000) throw new ClientApiException("Too many unverified engine activities; verify completion first.");
        activities.put(pending, component);
        persistActivities();
    }

    private void recordSuccessfulAction(String pending, String component, boolean tracked, Object result)
            throws ClientApiException {
        if (pending == null) return;
        if (tracked && (!(result instanceof ApiResponseElement response) || response.getValue() == null
                || !response.getValue().matches("[0-9]+"))) {
            quarantined = true;
            throw new ClientApiException("Engine activity start outcome is unknown; operator reconciliation required.");
        }
        synchronized (this) {
            activities.remove(pending);
            if (tracked) activities.put(component + ":" + ((ApiResponseElement) result).getValue(), component);
            persistActivities();
        }
    }

    private void recordFailedAction(String pending, String component, String type, String method, Exception failure) {
        // A disconnected request can still be running remotely. Leave its durable pending entry.
        if (pending == null) return;
        if (failure instanceof ClientApiException apiException && definiteNoStart(component, type, method, apiException)) {
            synchronized (this) { activities.remove(pending); persistActivities(); }
        } else quarantined = true;
    }

    private boolean definiteNoStart(String component, String type, String method, ClientApiException failure) {
        // These codes are dispatcher rejections before an action handler runs.
        if ("bad_action".equals(failure.getCode()) || "no_implementor".equals(failure.getCode())) return true;
        // Preserve the existing queue's busy/no-start contract only for scanner launch APIs.
        return "scan_in_progress".equals(failure.getCode()) && "action".equals(type)
                && java.util.Set.of("spider", "ascan", "ajaxSpider", "clientSpider").contains(component)
                && ("scan".equals(method) || "scanAsUser".equals(method));
    }

    private void observeCompletion(String component, String method, Map<String, String> params, Object response) {
        if (!properties.isUpdatesEnabled() || params == null) return;
        String id = null;
        if ("clientSpider".equals(component) && "status".equals(method)
                && response instanceof ApiResponseElement element && "100".equals(element.getValue())) {
            id = params.get("scanId");
        } else if ("automation".equals(component) && "planProgress".equals(method)
                && response instanceof ApiResponseSet set && set.getValue("finished") instanceof ApiResponseElement element) {
            try { java.time.Instant.parse(element.getValue()); id = params.get("planId"); }
            catch (java.time.format.DateTimeParseException | NullPointerException ignored) { return; }
        }
        if (id != null && id.matches("[0-9]+") && trackedActivities().containsKey(component + ":" + id)) {
            completedActivity(component + ":" + id);
        }
    }

    private synchronized void persistActivities() { persist("activities", String.join("\n", activities.keySet())); }

    private void restoreRecordedState() {
        quarantined = Files.exists(directory.resolve("update.pending"));
        Path tracked = directory.resolve("activities");
        if (Files.exists(tracked)) {
            try {
                for (String line : Files.readAllLines(tracked, StandardCharsets.UTF_8)) {
                    String[] parts = line.split(":", -1);
                    if (parts.length != 2 || !parts[0].matches("(?:automation|clientSpider)")
                            || !parts[1].matches("[0-9]+")) {
                        quarantined = true;
                        break;
                    }
                    activities.put(line, parts[0]);
                }
                if (activities.size() > 1000) quarantined = true;
            } catch (IOException e) {
                quarantined = true;
            }
        }
    }

    private void persist(String name, String value) {
        try {
            Files.createDirectories(directory);
            Path temp = directory.resolve(name + ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            quarantined = true;
            throw new IllegalStateException("Cannot persist engine maintenance state; engine actions remain quarantined.");
        }
    }

    private void requireMaintenanceOwner() {
        if (!lock.isWriteLockedByCurrentThread()) throw new IllegalStateException("Maintenance permit required.");
    }
}
