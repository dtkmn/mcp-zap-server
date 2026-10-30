package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/** Only the official Marketplace ID operation is exposed by this product boundary. */
@Component
public class ZapEngineAddonAccess {
    public static final int MAX_ADDONS = 1024;
    private final ClientApi zap;

    public ZapEngineAddonAccess(ClientApi zap) { this.zap = zap; }

    public record Addon(String id, String version, String status, String installationStatus) { }
    public record PassiveRule(String id, String enabled, String alertThreshold) { }
    public record Inventory(String engineVersion, List<Addon> installed, List<Addon> local) { }

    public Inventory inventory() throws ClientApiException {
        String version = value(zap.core.version());
        if (version.isBlank()) throw malformed();
        return new Inventory(version, addons(zap.autoupdate.installedAddons()),
                addons(zap.autoupdate.localAddons()));
    }

    public List<Addon> marketplace() throws ClientApiException { return addons(zap.autoupdate.marketplaceAddons()); }

    public List<PassiveRule> passiveRules() throws ClientApiException {
        List<PassiveRule> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ApiResponse response : list(zap.pscan.scanners()).getItems()) {
            if (!(response instanceof ApiResponseSet set)) throw malformed();
            String id = value(set.getValue("id"));
            String enabled = value(set.getValue("enabled"));
            String threshold = value(set.getValue("alertThreshold"));
            if (!id.matches("[0-9]{1,10}") || !ids.add(id)
                    || !(enabled.equalsIgnoreCase("true") || enabled.equalsIgnoreCase("false"))
                    || !Set.of("OFF", "LOW", "MEDIUM", "HIGH", "DEFAULT").contains(threshold.toUpperCase(java.util.Locale.ROOT))) {
                throw malformed();
            }
            result.add(new PassiveRule(id, enabled, threshold));
        }
        return List.copyOf(result);
    }

    public void install(String addonId) throws ClientApiException {
        if (!"OK".equalsIgnoreCase(value(zap.autoupdate.installAddon(addonId)))) {
            throw new ClientApiException("The install action did not confirm completion.");
        }
    }

    /** Unknown status is never interpreted as idle. The transport gate is already held exclusively. */
    public void requireQuiescent(EngineMaintenanceGate gate, Inventory inventory) throws ClientApiException {
        // These APIs do not offer engine-wide enumeration. Persist starts at the shared transport.
        for (Map.Entry<String, String> activity : gate.trackedActivities().entrySet()) {
            String[] key = activity.getKey().split(":", -1);
            if (key.length != 2 || !key[1].matches("[0-9]+")) throw malformed();
            if ("automation".equals(key[0])) {
                ApiResponse progress = zap.automation.planProgress(key[1]);
                if (!(progress instanceof ApiResponseSet set)) throw malformed();
                ApiResponse finished = set.getValue("finished");
                if (!(finished instanceof ApiResponseElement element) || element.getValue() == null
                        || element.getValue().isBlank()) throw busy();
                try { java.time.Instant.parse(element.getValue()); }
                catch (java.time.format.DateTimeParseException e) { throw malformed(); }
            } else if ("clientSpider".equals(key[0])) {
                if (integer(value(zap.clientSpider.status(key[1]))) != 100) throw busy();
            } else throw malformed();
            gate.completedActivity(activity.getKey());
        }
        // A finished plan/browser can have just queued passive work. Inspect that work last.
        requireFinishedScans(zap.spider.scans());
        requireFinishedScans(zap.ascan.scans());
        if (hasInstalled(inventory, "spiderAjax")) {
            if (!"stopped".equalsIgnoreCase(value(zap.ajaxSpider.status()))) throw busy();
        }
        if (integer(value(zap.pscan.recordsToScan())) != 0) throw busy();
        ApiResponse tasks = zap.pscan.currentTasks();
        int taskCount;
        if (tasks instanceof ApiResponseList entries) taskCount = entries.getItems().size();
        else if (tasks instanceof ApiResponseSet entries) taskCount = entries.getValues().size();
        else taskCount = integer(value(tasks));
        if (taskCount != 0) throw busy();
    }

    public static boolean hasInstalled(Inventory inventory, String id) {
        return inventory.installed().stream().anyMatch(addon -> addon.id().equals(id));
    }

    private void requireFinishedScans(ApiResponse response) {
        for (ApiResponse entry : list(response).getItems()) {
            if (!(entry instanceof ApiResponseSet set)) throw malformed();
            if (integer(value(set.getValue("progress"))) != 100) throw busy();
        }
    }

    private static List<Addon> addons(ApiResponse response) {
        List<Addon> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ApiResponse entry : list(response).getItems()) {
            if (!(entry instanceof ApiResponseSet set)) throw malformed();
            String id = value(set.getValue("id"));
            String version = value(set.getValue("version"));
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !ids.add(id)
                    || !version.matches("[0-9]+(?:\\.[0-9]+){0,3}")) throw malformed();
            result.add(new Addon(id, version, value(set.getValue("status")), value(set.getValue("installationStatus"))));
        }
        return List.copyOf(result);
    }

    private static ApiResponseList list(ApiResponse response) {
        if (!(response instanceof ApiResponseList list) || list.getItems().size() > MAX_ADDONS) throw malformed();
        return list;
    }

    private static String value(ApiResponse response) {
        if (!(response instanceof ApiResponseElement element) || element.getValue() == null
                || element.getValue().length() > 128) throw malformed();
        return element.getValue().trim();
    }

    private int integer(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw malformed();
            return parsed;
        } catch (NumberFormatException e) { throw malformed(); }
    }

    private static IllegalStateException malformed() { return new IllegalStateException("Engine inventory or activity response is unavailable or malformed."); }
    private static IllegalStateException busy() { return new IllegalStateException("Engine work or passive scanning is still active; wait for completion before updating."); }
}
