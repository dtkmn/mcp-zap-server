package mcp.server.zap.core.gateway;

/**
 * Gateway-facing access contract for runtime health and startup configuration.
 */
public interface EngineRuntimeAccess {

    String readVersion();

    void applyNetworkDefaults(NetworkDefaults defaults);

    record NetworkDefaults(
            String userAgent,
            int connectionTimeoutInSecs,
            int dnsTtlSuccessfulQueries
    ) {
    }
}
