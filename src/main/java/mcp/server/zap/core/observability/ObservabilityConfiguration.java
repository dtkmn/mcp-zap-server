package mcp.server.zap.core.observability;

import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared observability infrastructure beans.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    AuditEventRepository auditEventRepository(ObservabilityProperties properties) {
        int capacity = Math.max(50, properties.getAudit().getMaxEvents());
        return new InMemoryAuditEventRepository(capacity);
    }
}
