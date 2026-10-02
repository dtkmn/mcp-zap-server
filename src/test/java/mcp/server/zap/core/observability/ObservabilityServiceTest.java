package mcp.server.zap.core.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import mcp.gateway.core.audit.GatewayAuditEvent;
import mcp.gateway.core.protection.McpAbuseProtectionDecision;
import mcp.gateway.spring.webflux.McpAdapterRejectionReason;
import mcp.server.zap.core.gateway.GatewayCoreAuditAdapter;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ObservabilityServiceTest {

    @Test
    void arbitraryHttpMethodsShareOneMetricWithoutLosingResponseDimensions() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityService service = service(meters, new ArrayList<>());

        for (int i = 0; i < 500; i++) {
            service.recordHttpRequest("EXTENSION" + i, "/mcp", 401, "anonymous", Duration.ofMillis(2));
        }
        service.recordHttpRequest("GET", "/auth/validate", 200, "client", Duration.ofMillis(3));

        assertThat(meters.find("mcp.zap.http.requests").timers()).hasSize(2);
        assertThat(meters.get("mcp.zap.http.requests")
                .tags("method", "other", "path", "/mcp", "status", "401",
                        "outcome", "client_error", "authenticated", "false")
                .timer().count()).isEqualTo(500);
        assertThat(meters.get("mcp.zap.http.requests")
                .tags("method", "get", "path", "/auth/validate", "status", "200",
                        "outcome", "success", "authenticated", "true")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void concurrentHttpMetricRegistrationIsBoundedAndOverflowStillCountsRequests() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityService service = service(meters, new ArrayList<>());
        List<Callable<Void>> work = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            int number = i;
            work.add(() -> {
                int status = switch (number % 4) {
                    case 0 -> 200;
                    case 1 -> 401;
                    case 2 -> 500;
                    default -> 0;
                };
                service.recordHttpRequest("GET", "/route-" + number, status,
                        number % 2 == 0 ? "client" : "anonymous", Duration.ofMillis(1));
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(work)) {
                result.get();
            }
        }

        var timers = meters.find("mcp.zap.http.requests").timers();
        assertThat(timers.size()).isLessThanOrEqualTo(1024);
        assertThat(timers.stream().mapToLong(timer -> timer.count()).sum()).isEqualTo(4000);
        assertThat(timers).anyMatch(timer -> "/overflow".equals(timer.getId().getTag("path")));
    }

    @Test
    void invalidHttpStatusesAndMissingRoutesUseFixedLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservabilityService service = service(meters, new ArrayList<>());
        for (int i = 0; i < 500; i++) {
            service.recordHttpRequest(null, null, 1000 + i, null, Duration.ZERO);
        }

        assertThat(meters.find("mcp.zap.http.requests").timers()).hasSize(1);
        assertThat(meters.get("mcp.zap.http.requests")
                .tags("method", "unknown", "path", "/unmatched", "status", "unknown",
                        "outcome", "server_error", "authenticated", "false")
                .timer().count()).isEqualTo(500);
    }

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
