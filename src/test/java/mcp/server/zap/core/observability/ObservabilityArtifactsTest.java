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
                .contains("asg_http_requests_seconds_count")
                .contains("asg_auth_events_total")
                .contains("asg_authorization_decisions_total")
                .contains("asg_tool_executions_seconds_count")
                .contains("asg_protection_rejections_total")
                .contains("asg_queue_jobs")
                .contains("asg_audit_events_total");

        assertThat(alerts)
                .contains("asg_auth_events_total")
                .contains("asg_protection_rejections_total")
                .contains("asg_queue_jobs")
                .contains("asg_tool_executions_seconds_count");
    }
}
