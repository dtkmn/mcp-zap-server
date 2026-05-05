package mcp.server.zap.core.service.authz;

import java.util.List;
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
    void findingsSummaryRequiresAlertReadScope() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolCall(List.of("zap:report:read"), "zap_get_findings_summary");
        ToolAuthorizationDecision allowed = service.authorizeToolCall(List.of("zap:alerts:read"), "zap_get_findings_summary");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).containsExactly("zap:alerts:read");
        assertThat(denied.missingScopes()).containsExactly("zap:alerts:read");
        assertThat(allowed.allowed()).isTrue();
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
    void policyDryRunRequiresDedicatedPolicyScope() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision denied = service.authorizeToolCall(List.of("mcp:tools:list"), "zap_policy_dry_run");
        ToolAuthorizationDecision allowed = service.authorizeToolCall(List.of("zap:policy:dry-run"), "zap_policy_dry_run");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.requiredScopes()).containsExactly("zap:policy:dry-run");
        assertThat(allowed.allowed()).isTrue();
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
    void unmappedToolIsDeniedByDefault() {
        ToolAuthorizationService service = createService(ToolAuthorizationProperties.Mode.ENFORCE, true);

        ToolAuthorizationDecision decision = service.authorizeToolCall(List.of("*"), "zap_unknown_tool");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.mapped()).isFalse();
    }

    private ToolAuthorizationService createService(ToolAuthorizationProperties.Mode mode, boolean allowWildcard) {
        ToolAuthorizationProperties properties = new ToolAuthorizationProperties();
        properties.setMode(mode);
        properties.setAllowWildcard(allowWildcard);
        return new ToolAuthorizationService(new ToolScopeRegistry(), properties);
    }
}
