package mcp.server.zap.core.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ZapInitializationProperties;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.NetworkDefaults;
import org.springframework.stereotype.Service;

/**
 * Service to initialize ZAP with custom settings at application startup.
 * Configures User-Agent, timeouts, and DNS settings via the Network component API.
 */
@Slf4j
@Service
public class ZapInitializationService {

    private final EngineRuntimeAccess runtimeAccess;
    private final ZapInitializationProperties properties;

    /**
     * Build-time dependency injection constructor.
     */
    public ZapInitializationService(EngineRuntimeAccess runtimeAccess, ZapInitializationProperties properties) {
        this.runtimeAccess = runtimeAccess;
        this.properties = properties;
    }

    /**
     * Apply startup hardening and browser-like network defaults to running ZAP.
     */
    @PostConstruct
    public void initializeZapSettings() {
        try {
            log.info("Initializing ZAP with browser-like settings to bypass WAF...");

            runtimeAccess.applyNetworkDefaults(new NetworkDefaults(
                    properties.getUserAgent(),
                    properties.getConnectionTimeoutInSecs(),
                    properties.getDnsTtlSuccessfulQueries()
            ));

            log.info("ZAP initialization completed successfully");

        } catch (Exception e) {
            log.error("Failed to initialize ZAP settings: {}", e.getMessage(), e);
            // Don't throw - allow application to start even if some settings fail
        }
    }
}
