package mcp.server.zap.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.configuration.ZapInitializationProperties;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * Service to initialize ZAP with custom settings at application startup.
 * Configures User-Agent, timeouts, and DNS settings via the Network component API.
 */
@Slf4j
@Service
public class ZapInitializationService {

    private final ClientApi zap;
    private final ZapInitializationProperties properties;

    public ZapInitializationService(ClientApi zap, ZapInitializationProperties properties) {
        this.zap = zap;
        this.properties = properties;
    }

    @PostConstruct
    public void initializeZapSettings() {
        try {
            log.info("Initializing ZAP with browser-like settings to bypass WAF...");

            // Set realistic User-Agent to avoid WAF blocking (Network component API)
            try {
                zap.network.setDefaultUserAgent(properties.getUserAgent());
                log.info("Set User-Agent: {}", properties.getUserAgent());
            } catch (ClientApiException e) {
                log.warn("Could not set User-Agent: {}", e.getMessage());
            }

            // Set connection timeout (Network component API - takes String parameter)
            try {
                zap.network.setConnectionTimeout(String.valueOf(properties.getConnectionTimeoutInSecs()));
                log.info("Set connection timeout to {} seconds", properties.getConnectionTimeoutInSecs());
            } catch (ClientApiException e) {
                log.warn("Could not set connection timeout: {}", e.getMessage());
            }

            // Enable DNS TTL to respect DNS caching (Network component API - takes String parameter)
            try {
                zap.network.setDnsTtlSuccessfulQueries(String.valueOf(properties.getDnsTtlSuccessfulQueries()));
                log.info("Set DNS TTL for successful queries to {} seconds", properties.getDnsTtlSuccessfulQueries());
            } catch (ClientApiException e) {
                log.debug("Could not set DNS TTL: {}", e.getMessage());
            }

            log.info("ZAP initialization completed successfully");

        } catch (Exception e) {
            log.error("Failed to initialize ZAP settings: {}", e.getMessage(), e);
            // Don't throw - allow application to start even if some settings fail
        }
    }
}
