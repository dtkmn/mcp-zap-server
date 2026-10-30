package mcp.server.zap.core.service.authz;

import java.util.List;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.gateway.core.invocation.McpToolInvocation;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolAuthorizationServiceTest {

    @Test
    void wildcardScopeAllowsMappedToolCalls() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision decision = service.authorizeToolCall(List.of("*"), "zap_report_read");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.requiredScopes()).containsExactly("zap:report:read");
    }

    @Test
    void explicitMissingScopeIsRejected() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision decision = service.authorizeToolCall(List.of("mcp:tools:list"), "zap_report_read");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingScopes()).containsExactly("zap:report:read");
    }

    @Test
    void retainedFindingsSnapshotRequiresAlertReadScope() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolCall(List.of("zap:report:read"), "zap_findings_snapshot");
        ToolAuthorizationDecision allowed = service.authorizeToolCall(List.of("zap:alerts:read"), "zap_findings_snapshot");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).containsExactly("zap:alerts:read");
        assertThat(denied.missingScopes()).containsExactly("zap:alerts:read");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void policyReadScopeAllowsInspectionWithoutGrantingScanOrEditingAccess() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);
        for (String tool : List.of("zap_scan_policies_list", "zap_scan_policy_view")) {
            assertThat(service.authorizeToolCall(List.of("zap:scan:policy:read"), tool).allowed()).isTrue();
            assertThat(service.authorizeToolCall(List.of("*"), tool).allowed()).isTrue();
            for (String unrelatedScope : List.of("zap:scan:read", "zap:scan:attack:run", "zap:scan:policy:write")) {
                ToolAuthorizationDecision denied = service.authorizeToolCall(List.of(unrelatedScope), tool);
                assertThat(denied.allowed()).isFalse();
                assertThat(denied.missingScopes()).containsExactly("zap:scan:policy:read");
            }
        }
        ToolAuthorizationDecision attack = service.authorizeToolCall(List.of("zap:scan:policy:read"), "zap_attack_start");
        assertThat(attack.allowed()).isFalse();
        assertThat(attack.missingScopes()).containsExactly("zap:scan:attack:run");
        ToolAuthorizationDecision editing = service.authorizeToolCall(List.of("zap:scan:policy:read"), "zap_scan_policy_rule_set");
        assertThat(editing.allowed()).isFalse();
        assertThat(editing.mapped()).isFalse();
    }

    @Test
    void wildcardScopeCanBeDisabled() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, false);

        ToolAuthorizationDecision decision = service.authorizeToolCall(List.of("*"), "zap_report_read");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingScopes()).containsExactly("zap:report:read");
    }

    @Test
    void toolsListRequiresDedicatedDiscoveryScope() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolsList(List.of("zap:report:read"));
        ToolAuthorizationDecision allowed = service.authorizeToolsList(List.of("mcp:tools:list"));

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).containsExactly("mcp:tools:list");
        assertThat(allowed.allowed()).isTrue();
    }

    @Test
    void retiredPolicyDryRunCannotBeAuthorizedByItsOldScopeOrWildcard() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolCall(List.of("mcp:tools:list"), "zap_policy_dry_run");
        ToolAuthorizationDecision allowed = service.authorizeToolCall(List.of("zap:policy:dry-run"), "zap_policy_dry_run");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).isEmpty();
        assertThat(allowed.allowed()).isFalse();
        assertThat(service.authorizeToolCall(List.of("*"), "zap_policy_dry_run").allowed()).isFalse();
    }

    @Test
    void guidedAuthSessionToolsRequireDedicatedScopes() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision deniedPrepare = service.authorizeToolCall(List.of("zap:scan:read"), "zap_auth_session_prepare");
        ToolAuthorizationDecision allowedPrepare = service.authorizeToolCall(List.of("zap:auth:session:write"), "zap_auth_session_prepare");
        ToolAuthorizationDecision deniedValidate = service.authorizeToolCall(List.of("zap:scan:read"), "zap_auth_session_validate");
        ToolAuthorizationDecision allowedValidate = service.authorizeToolCall(List.of("zap:auth:test"), "zap_auth_session_validate");

        assertThat(deniedPrepare.allowed()).isFalse();
        assertThat(deniedPrepare.requiredScopes()).containsExactly("zap:auth:session:write");
        assertThat(allowedPrepare.allowed()).isTrue();
        assertThat(deniedValidate.allowed()).isFalse();
        assertThat(deniedValidate.requiredScopes()).containsExactly("zap:auth:test");
        assertThat(allowedValidate.allowed()).isTrue();
    }

    @Test
    void releaseEvidenceExportRequiresScanReadScope() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolCall(List.of("zap:report:read"), "zap_scan_history_release_evidence");
        ToolAuthorizationDecision allowed = service.authorizeToolCall(List.of("zap:scan:read"), "zap_scan_history_release_evidence");
        ToolAuthorizationDecision customerHandoffAllowed =
                service.authorizeToolCall(List.of("zap:scan:read"), "zap_scan_history_customer_handoff");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).containsExactly("zap:scan:read");
        assertThat(allowed.allowed()).isTrue();
        assertThat(customerHandoffAllowed.allowed()).isTrue();
    }

    @Test
    void unmappedToolIsDeniedByDefault() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision decision = service.authorizeToolCall(List.of("*"), "zap_unknown_tool");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.mapped()).isFalse();
    }

    @Test
    void disabledModeBypassesMappedScopeChecksButStillDeniesUnmappedTools() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.OFF, true);

        ToolAuthorizationDecision mapped = service.authorizeToolCall(List.of(), "zap_report_read");
        ToolAuthorizationDecision unmapped = service.authorizeToolCall(List.of("*"), "zap_unknown_tool");

        assertThat(mapped.allowed()).isTrue();
        assertThat(mapped.mapped()).isTrue();
        assertThat(mapped.requiredScopes()).containsExactly("zap:report:read");
        assertThat(unmapped.allowed()).isFalse();
        assertThat(unmapped.mapped()).isFalse();
    }

    @Test
    void authorizesNormalizedGatewayToolContextThroughCoreAuthorizer() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);
        GatewayToolExecutionContext context = GatewayToolExecutionContext.of(
                "client",
                "workspace",
                "corr",
                McpToolInvocation.fromJsonRpc(McpToolInvocation.METHOD_TOOLS_CALL, "zap_report_read"),
                null
        );

        ToolAuthorizationDecision decision = service.authorize(List.of("zap:report:read"), context);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.actionName()).isEqualTo("zap_report_read");
    }

    private ToolAuthorizationService createService(ToolAuthorizationProperties.Mode mode, boolean allowWildcard) {
        ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
        properties.setMode(mode);
        properties.setAllowWildcard(allowWildcard);
        return new ToolAuthorizationService(new ToolScopeRegistry(), properties);
    }
}
