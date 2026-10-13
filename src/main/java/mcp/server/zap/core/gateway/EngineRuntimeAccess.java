package mcp.server.zap.core.gateway;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Gateway-facing access contract for runtime health and startup configuration.
 */
public interface EngineRuntimeAccess {

    String readVersion();

    default ScannerInventory readScannerInventory() {
        return ScannerInventory.unknown();
    }

    void applyNetworkDefaults(NetworkDefaults defaults);

    record ScannerInventory(String zapVersion, List<AddOn> addOns, boolean inventoryComplete) {

        public static final int MAX_ADD_ONS = 1024;

        public ScannerInventory {
            zapVersion = AddOn.normalizeVersion(zapVersion);
            inventoryComplete &= zapVersion != null && addOns != null && addOns.size() <= MAX_ADD_ONS;
            TreeMap<String, AddOn> installed = new TreeMap<>();
            Set<String> seenIds = new HashSet<>();
            int inspected = 0;
            for (AddOn addOn : addOns == null ? List.<AddOn>of() : addOns) {
                if (inspected++ >= MAX_ADD_ONS) {
                    break;
                }
                if (addOn == null || addOn.id() == null) {
                    inventoryComplete = false;
                } else if (!seenIds.add(addOn.id())) {
                    installed.remove(addOn.id());
                    inventoryComplete = false;
                } else if (addOn.version() == null || addOn.status() == null) {
                    inventoryComplete = false;
                } else {
                    installed.put(addOn.id(), addOn);
                }
            }
            addOns = List.copyOf(installed.values());
        }

        public static ScannerInventory unknown() {
            return new ScannerInventory(null, List.of(), false);
        }
    }

    /** Installed add-on identity; status is its maturity, not installation state. */
    record AddOn(String id, String version, String status) {

        private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
        private static final Set<String> STATUSES = Set.of("unknown", "example", "alpha", "beta", "weekly", "release");

        public AddOn {
            id = normalizeToken(id, ID_PATTERN);
            version = normalizeVersion(version);
            status = status == null ? null : status.trim();
            if (status != null && !STATUSES.contains(status)) {
                status = null;
            }
        }

        private static String normalizeVersion(String value) {
            return normalizeToken(value, VERSION_PATTERN);
        }

        private static String normalizeToken(String value, Pattern pattern) {
            String normalized = value == null ? null : value.trim();
            return normalized != null && pattern.matcher(normalized).matches() ? normalized : null;
        }
    }

    record NetworkDefaults(
            String userAgent,
            int connectionTimeoutInSecs,
            int dnsTtlSuccessfulQueries
    ) {
    }
}
