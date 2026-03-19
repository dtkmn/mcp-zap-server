package mcp.server.zap.core.service.protection;

import io.micrometer.core.instrument.MeterRegistry;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks lightweight in-memory direct-scan and automation executions for quota and overload checks.
 */
@Service
public class OperationRegistry {
    private static final String DIRECT_SCAN_TYPE = "direct-scan";
    private static final String AUTOMATION_PLAN_TYPE = "automation-plan";

    private final AbuseProtectionProperties properties;
    private final ConcurrentHashMap<String, OperationState> operations = new ConcurrentHashMap<>();

    public OperationRegistry(AbuseProtectionProperties properties) {
        this(properties, null);
    }

    @Autowired
    public OperationRegistry(AbuseProtectionProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.properties = properties;
        registerGauges(meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null);
    }

    public void registerDirectScan(String operationId, String workspaceId) {
        register(operationId, workspaceId, DIRECT_SCAN_TYPE);
    }

    public void touchDirectScan(String operationId) {
        touch(operationId);
    }

    public void releaseDirectScan(String operationId) {
        operations.remove(operationId);
    }

    public void releaseDirectScansByPrefix(String prefix) {
        cleanupExpired();
        for (String operationId : operations.keySet()) {
            if (operationId != null && operationId.startsWith(prefix)) {
                operations.remove(operationId);
            }
        }
    }

    public void touchDirectScansByPrefix(String prefix) {
        cleanupExpired();
        for (Map.Entry<String, OperationState> entry : operations.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(prefix)) {
                entry.getValue().lastTouchedAt = Instant.now();
            }
        }
    }

    public void registerAutomationPlan(String planId, String workspaceId) {
        register(planId, workspaceId, AUTOMATION_PLAN_TYPE);
    }

    public void touchAutomationPlan(String planId) {
        touch(planId);
    }

    public void releaseAutomationPlan(String planId) {
        operations.remove(planId);
    }

    public int countDirectScans() {
        cleanupExpired();
        return (int) operations.values().stream()
                .filter(state -> DIRECT_SCAN_TYPE.equals(state.type))
                .count();
    }

    public int countDirectScans(String workspaceId) {
        cleanupExpired();
        return (int) operations.values().stream()
                .filter(state -> DIRECT_SCAN_TYPE.equals(state.type) && workspaceId.equals(state.workspaceId))
                .count();
    }

    public int countAutomationPlans() {
        cleanupExpired();
        return (int) operations.values().stream()
                .filter(state -> AUTOMATION_PLAN_TYPE.equals(state.type))
                .count();
    }

    public int countAutomationPlans(String workspaceId) {
        cleanupExpired();
        return (int) operations.values().stream()
                .filter(state -> AUTOMATION_PLAN_TYPE.equals(state.type) && workspaceId.equals(state.workspaceId))
                .count();
    }

    private void register(String operationId, String workspaceId, String type) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        cleanupExpired();
        operations.put(operationId, new OperationState(normalizeWorkspaceId(workspaceId), type, Instant.now()));
    }

    private void touch(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        OperationState state = operations.get(operationId);
        if (state != null) {
            state.lastTouchedAt = Instant.now();
        }
    }

    private void cleanupExpired() {
        long staleAfterSeconds = Math.max(60L, properties.getOperationStaleAfterSeconds());
        Instant cutoff = Instant.now().minusSeconds(staleAfterSeconds);
        operations.entrySet().removeIf(entry -> entry.getValue().lastTouchedAt.isBefore(cutoff));
    }

    private String normalizeWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return "default-workspace";
        }
        return workspaceId.trim();
    }

    private void registerGauges(MeterRegistry meterRegistry) {
        if (meterRegistry == null) {
            return;
        }

        meterRegistry.gauge("asg.operations.active", java.util.List.of(io.micrometer.core.instrument.Tag.of("type", "direct_scan")),
                this, registry -> registry.countDirectScans());
        meterRegistry.gauge("asg.operations.active", java.util.List.of(io.micrometer.core.instrument.Tag.of("type", "automation_plan")),
                this, registry -> registry.countAutomationPlans());
    }

    private static final class OperationState {
        private final String workspaceId;
        private final String type;
        private volatile Instant lastTouchedAt;

        private OperationState(String workspaceId, String type, Instant lastTouchedAt) {
            this.workspaceId = workspaceId;
            this.type = type;
            this.lastTouchedAt = lastTouchedAt;
        }
    }
}
