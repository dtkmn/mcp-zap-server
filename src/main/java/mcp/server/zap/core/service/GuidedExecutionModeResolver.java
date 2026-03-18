package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.QueueCoordinatorProperties;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Chooses the default execution mode for guided tools based on deployment topology.
 */
@Component
public class GuidedExecutionModeResolver {

    public enum ExecutionMode {
        DIRECT,
        QUEUE
    }

    private final ScanJobStoreProperties scanJobStoreProperties;
    private final QueueCoordinatorProperties queueCoordinatorProperties;

    public GuidedExecutionModeResolver(ObjectProvider<ScanJobStoreProperties> scanJobStorePropertiesProvider,
                                       ObjectProvider<QueueCoordinatorProperties> queueCoordinatorPropertiesProvider) {
        this.scanJobStoreProperties = scanJobStorePropertiesProvider.getIfAvailable(ScanJobStoreProperties::new);
        this.queueCoordinatorProperties = queueCoordinatorPropertiesProvider.getIfAvailable(QueueCoordinatorProperties::new);
    }

    public ExecutionMode resolveDefaultMode() {
        return preferQueue() ? ExecutionMode.QUEUE : ExecutionMode.DIRECT;
    }

    public boolean preferQueue() {
        String storeBackend = normalize(scanJobStoreProperties.getBackend(), "in-memory");
        String coordinatorBackend = normalize(queueCoordinatorProperties.getBackend(), "single-node");
        return "postgres".equals(storeBackend) || "postgres-lock".equals(coordinatorBackend);
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase();
    }
}
