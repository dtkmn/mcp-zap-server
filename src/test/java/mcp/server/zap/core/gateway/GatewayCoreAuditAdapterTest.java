package mcp.server.zap.core.gateway;

import java.util.Map;
import mcp.gateway.core.audit.GatewayAuditEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCoreAuditAdapterTest {
    private final GatewayCoreAuditAdapter adapter = new GatewayCoreAuditAdapter();

    @Test
    void adaptsPolicyDecisionPayloadIntoGatewayCoreAuditEvent() {
        GatewayAuditEvent event = adapter.policyDecision(
                " Client-A ",
                " Workspace-One ",
                "dry_run_deny",
                "corr-1",
                Map.of("tool", "zap_attack_start")
        );

        assertThat(event.type()).isEqualTo("policy_decision");
        assertThat(event.principal()).isEqualTo("Client-A");
        assertThat(event.outcome()).isEqualTo("dry_run_deny");
        assertThat(event.details())
                .containsEntry("correlationId", "corr-1")
                .containsEntry("clientId", "client_a")
                .containsEntry("workspaceId", "workspace_one")
                .containsEntry("outcome", "dry_run_deny")
                .containsEntry("tool", "zap_attack_start");
    }

    @Test
    void usesStableDefaultsForAnonymousPolicyAuditEvents() {
        GatewayAuditEvent event = adapter.policyDecision(null, null, "deny", null, null);

        assertThat(event.principal()).isEqualTo("anonymous");
        assertThat(event.details())
                .containsEntry("clientId", "anonymous")
                .containsEntry("workspaceId", "default_workspace")
                .containsEntry("outcome", "deny")
                .doesNotContainKey("correlationId");
    }

    @Test
    @SuppressWarnings("unchecked")
    void extensionDetailsCannotSpoofTrustedAuditIdentityFields() {
        GatewayAuditEvent event = adapter.policyDecision(
                "real-client",
                "real-workspace",
                "deny",
                "real-correlation",
                Map.of(
                        "tool", "zap_attack_start",
                        "clientId", "spoofed-client",
                        "workspaceId", "spoofed-workspace",
                        "correlationId", "spoofed-correlation",
                        "outcome", "allow",
                        "extensionDetails", Map.of("reason", "spoof attempt")
                )
        );

        assertThat(event.details())
                .containsEntry("tool", "zap_attack_start")
                .containsEntry("clientId", "real_client")
                .containsEntry("workspaceId", "real_workspace")
                .containsEntry("correlationId", "real-correlation")
                .containsEntry("outcome", "deny");
        assertThat((Map<String, Object>) event.details().get("extensionDetails"))
                .containsEntry("reason", "spoof attempt")
                .containsEntry("clientId", "spoofed-client")
                .containsEntry("workspaceId", "spoofed-workspace")
                .containsEntry("correlationId", "spoofed-correlation")
                .containsEntry("outcome", "allow");
    }
}
