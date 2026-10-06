package mcp.server.zap.core.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.ZapInitializationProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineRuntimeAccess;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.NetworkDefaults;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Service;

/**
 * Reconciles configured ZAP network defaults at startup, on health probes, and
 * before operations that can send requests to scan targets.
 */
@Slf4j
@Aspect
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
     * Attempt initial configuration without requiring ZAP to start first.
     */
    @PostConstruct
    public void initializeZapSettings() {
        try {
            ensureInitialized();
        } catch (Exception e) {
            log.warn("ZAP network configuration is pending; health probes and outbound operations will retry");
            log.debug("Initial ZAP network configuration failed", e);
        }
    }

    /**
     * Check live settings every time: a recovered engine can have the same
     * version but fresh defaults even when no health probe observed its outage.
     */
    public synchronized String ensureInitialized() {
        String version = runtimeAccess.readVersion();
        runtimeAccess.applyNetworkDefaults(new NetworkDefaults(
                properties.getUserAgent(),
                properties.getConnectionTimeoutInSecs(),
                properties.getDnsTtlSuccessfulQueries()
        ));
        log.debug("Configured ZAP user agent and target connection timeout are confirmed");
        return version;
    }

    /**
     * Guard the engine boundary so queued and direct execution share the same
     * configuration requirement. Status, stop, and local report reads remain
     * available when an outbound operation cannot be configured.
     */
    @Before("execution(public * mcp.server.zap.core.gateway.ZapEngineScanExecution.start*(..))"
            + " || execution(public * mcp.server.zap.core.gateway.ZapEngineAjaxSpiderExecution.startAjaxSpider(..))"
            + " || execution(public * mcp.server.zap.core.gateway.ZapEngineApiImportAccess.import*(..))"
            + " || execution(public * mcp.server.zap.core.gateway.ZapEngineAutomationAccess.runAutomationPlan(..))"
            + " || execution(public * mcp.server.zap.core.gateway.ZapEngineContextAccess.testUserAuthentication(..))")
    public void ensureBeforeOutboundOperation() {
        try {
            ensureInitialized();
        } catch (RuntimeException e) {
            throw ZapApiException.beforeDispatch("ZAP network configuration is not ready", e);
        }
    }
}
