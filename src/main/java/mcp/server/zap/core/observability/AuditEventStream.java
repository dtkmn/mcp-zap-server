package mcp.server.zap.core.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.audit.GatewayAuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.stereotype.Service;

/**
 * Publishes bounded audit events to the Actuator audit endpoint and structured logs.
 */
@Service
public class AuditEventStream implements GatewayAuditSink {
    private static final Logger auditLog = LoggerFactory.getLogger("mcp.server.zap.audit");

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ObservabilityProperties properties;

    public AuditEventStream(AuditEventRepository auditEventRepository,
                            ObjectProvider<ObjectMapper> objectMapperProvider,
                            ObjectProvider<MeterRegistry> meterRegistryProvider,
                            ObservabilityProperties properties) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.properties = properties;
    }

    @Override
    public void publish(String type, String principal, String outcome, Map<String, Object> details) {
        publish(GatewayAuditEvent.of(type, principal, outcome, details));
    }

    @Override
    public void publish(GatewayAuditEvent event) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }

        GatewayAuditEvent auditEvent = event == null
                ? GatewayAuditEvent.of(null, null, null, Map.of())
                : event;
        String normalizedType = normalize(auditEvent.type(), "unknown");
        String normalizedPrincipal = hasText(auditEvent.principal()) ? auditEvent.principal().trim() : "anonymous";
        String normalizedOutcome = normalize(auditEvent.outcome(), "unknown");
        Map<String, Object> data = new LinkedHashMap<>();
        auditEvent.details().forEach(data::put);
        data.put("outcome", normalizedOutcome);

        auditEventRepository.add(new AuditEvent(Instant.now(), normalizedPrincipal, normalizedType, data));
        if (meterRegistry != null) {
            meterRegistry.counter("mcp.zap.audit.events", "type", normalizedType, "outcome", normalizedOutcome).increment();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("type", normalizedType);
        payload.put("principal", normalizedPrincipal);
        payload.put("outcome", normalizedOutcome);
        payload.put("data", data);

        try {
            auditLog.info("audit.event {}", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            auditLog.info("audit.event type={} principal={} outcome={} details={}",
                    normalizedType, normalizedPrincipal, normalizedOutcome, data);
        }
    }

    private String normalize(String value, String fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
