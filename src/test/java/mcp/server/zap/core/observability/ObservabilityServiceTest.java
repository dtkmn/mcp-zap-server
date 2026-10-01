package mcp.server.zap.core.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.spring.webflux.McpAdapterRejectionReason;
import mcp.server.zap.core.gateway.GatewayCoreAuditAdapter;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ObservabilityServiceTest {

    @Test
    void governanceMetricsRemainAvailableWithoutPublishingDuplicateAuditEvents() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<GatewayAuditEvent> events = new ArrayList<>();
        ObservabilityService service = service(meters, events);

        service.recordAuthorizationMetrics("zap_passive_scan_status", "warn", "insufficient_scope");
        service.recordProtectionRejectionMetrics(McpAbuseProtectionDecision.reject(
                "rate_limited", "client_request_rate", "zap_passive_scan_status", "client", "workspace", 7));
        service.recordInvalidMcpRequestMetrics("invalid_request_shape");
        service.recordAdapterRejectionMetrics(McpAdapterRejectionReason.UNKNOWN_TOOL);

        assertThat(meters.get("mcp.zap.authorization.decisions")
                .tags("action", "zap_passive_scan_status", "outcome", "warn", "reason", "insufficient_scope")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("mcp.zap.protection.rejections")
                .tags("error", "rate_limited", "reason", "client_request_rate")
                .counter().count()).isEqualTo(1);
        assertThat(meters.get("mcp.zap.invalid_mcp_requests")
                .tag("reason", "invalid_request_shape").counter().count()).isEqualTo(1);
        assertThat(meters.get("mcp.zap.adapter.rejections")
                .tag("reason", "unknown_tool").counter().count()).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    void allowedOrAbsentProtectionDecisionsDoNotCreateRejectionMetricsOrAudits() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<GatewayAuditEvent> events = new ArrayList<>();
        ObservabilityService service = service(meters, events);

        service.recordProtectionRejectionMetrics(null);
        service.recordProtectionRejectionMetrics(
                McpAbuseProtectionDecision.allow("zap_passive_scan_status", "client", "workspace"));

        assertThat(meters.find("mcp.zap.protection.rejections").counter()).isNull();
        assertThat(events).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private ObservabilityService service(SimpleMeterRegistry meters, List<GatewayAuditEvent> events) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(meters);
        return new ObservabilityService(provider, events::add,
                mock(ClientWorkspaceResolver.class), mock(GatewayCoreAuditAdapter.class));
    }
}
