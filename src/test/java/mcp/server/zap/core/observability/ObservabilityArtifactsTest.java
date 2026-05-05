package mcp.server.zap.core.observability;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityArtifactsTest {

    @Test
    void dashboardAndAlertArtifactsReferenceImplementedMetricFamilies() throws Exception {
        String dashboard = Files.readString(Path.of("ops/observability/grafana-dashboard.json"));
        String alerts = Files.readString(Path.of("ops/observability/prometheus-alerts.yml"));

        assertThat(dashboard)
                .contains("mcp_zap_http_requests_seconds_count")
                .contains("mcp_zap_auth_events_total")
                .contains("mcp_zap_authorization_decisions_total")
                .contains("mcp_zap_tool_executions_seconds_count")
                .contains("mcp_zap_protection_rejections_total")
                .contains("mcp_zap_queue_jobs")
                .contains("mcp_zap_audit_events_total");

        assertThat(alerts)
                .contains("mcp_zap_auth_events_total")
                .contains("mcp_zap_protection_rejections_total")
                .contains("mcp_zap_queue_jobs")
                .contains("mcp_zap_tool_executions_seconds_count");
    }
}
