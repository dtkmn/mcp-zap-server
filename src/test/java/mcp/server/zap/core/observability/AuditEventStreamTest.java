package mcp.server.zap.core.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import mcp.gateway.core.audit.GatewayAuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;

class AuditEventStreamTest {

    @Test
    void gatewayAuditEventOutcomeCannotBeOverwrittenByDetails() {
        InMemoryAuditEventRepository repository = new InMemoryAuditEventRepository(10);
        AuditEventStream stream = new AuditEventStream(
                repository,
                objectMapperProvider(),
                meterRegistryProvider(),
                new ObservabilityProperties()
        );

        stream.publish(GatewayAuditEvent.of(
                "policy_decision",
                "client-a",
                "deny",
                Map.of(
                        "outcome", "allow",
                        "tool", "zap_attack_start"
                )
        ));

        AuditEvent event = repository.find("client-a", Instant.EPOCH, "policy_decision").getFirst();
        assertThat(event.getData())
                .containsEntry("tool", "zap_attack_start")
                .containsEntry("outcome", "deny");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ObjectMapper> objectMapperProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> meterRegistryProvider() {
        return mock(ObjectProvider.class);
    }
}
