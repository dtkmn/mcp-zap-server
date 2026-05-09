package mcp.server.zap.core.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.extension.api.policy.PolicyEnforcementDecision;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BasicPolicyBundleToolExecutionPolicyHookTest {

    @Test
    void allowsToolExecutionWhenPolicyBundleMatchesAllowRule() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setBundle(policyBundle("deny", """
                {
                  "id": "allow-sandbox-attack",
                  "description": "Allow sandbox attack",
                  "decision": "allow",
                  "reason": "Sandbox target is approved.",
                  "match": {
                    "tools": ["zap_attack_start"],
                    "hosts": ["api.sandbox.example.com"]
                  }
                }
                """));

        PolicyEnforcementDecision decision = hook(properties).evaluate(new ToolExecutionPolicyContext(
                "zap_attack_start",
                "https://api.sandbox.example.com/orders",
                "corr-allow"
        ));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("Sandbox target is approved.");
        assertThat(decision.details())
                .containsEntry("policyProvider", "basic_policy_bundle")
                .containsEntry("policySource", "inline")
                .containsEntry("policyName", "basic-runtime-rules")
                .containsEntry("bundleName", "basic-runtime-rules")
                .containsEntry("decisionResult", "allow")
                .containsEntry("decisionSource", "rule")
                .containsEntry("matchedRuleId", "allow-sandbox-attack")
                .containsEntry("normalizedHost", "api.sandbox.example.com");
    }

    @Test
    void deniesToolExecutionWhenPolicyBundleMatchesDenyRule() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setBundle(policyBundle("allow", """
                {
                  "id": "deny-prod-active-scan",
                  "description": "Block production active scans",
                  "decision": "deny",
                  "reason": "Production active scans require a safer workflow.",
                  "match": {
                    "tools": ["zap_active_scan_start"],
                    "hosts": ["prod.example.com"]
                  }
                }
                """));

        PolicyEnforcementDecision decision = hook(properties).evaluate(new ToolExecutionPolicyContext(
                "zap_active_scan_start",
                "https://prod.example.com",
                "corr-deny"
        ));

        assertThat(decision.denied()).isTrue();
        assertThat(decision.reason()).isEqualTo("Production active scans require a safer workflow.");
        assertThat(decision.details())
                .containsEntry("policyName", "basic-runtime-rules")
                .containsEntry("decisionResult", "deny")
                .containsEntry("matchedRuleId", "deny-prod-active-scan")
                .containsEntry("defaultDecision", "allow");
    }

    @Test
    void abstainsWhenNoBasicPolicyBundleIsConfigured() {
        PolicyEnforcementDecision decision = hook(new PolicyEnforcementProperties()).evaluate(new ToolExecutionPolicyContext(
                "zap_attack_start",
                "https://api.sandbox.example.com",
                "corr-none"
        ));

        assertThat(decision.abstained()).isTrue();
        assertThat(decision.reason()).isEqualTo("no_policy_bundle_configured");
        assertThat(decision.details())
                .containsEntry("policyProvider", "basic_policy_bundle")
                .containsEntry("policySource", "none");
    }

    @Test
    void deniesWhenConfiguredPolicyBundleIsInvalid() {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setBundle("{not-json");

        PolicyEnforcementDecision decision = hook(properties).evaluate(new ToolExecutionPolicyContext(
                "zap_attack_start",
                "https://api.sandbox.example.com",
                "corr-invalid"
        ));

        assertThat(decision.denied()).isTrue();
        assertThat(decision.details())
                .containsEntry("policyProvider", "basic_policy_bundle")
                .containsEntry("policyError", "request_or_bundle_invalid")
                .containsEntry("decisionResult", "invalid")
                .containsEntry("validationValid", false);
        assertThat((List<String>) decision.details().get("validationErrors"))
                .anyMatch(error -> error.contains("policyBundle must be valid JSON"));
    }

    @Test
    void readsPolicyBundleFromConfiguredFile(@TempDir Path tempDir) throws Exception {
        Path bundleFile = tempDir.resolve("runtime-policy.json");
        Files.writeString(bundleFile, policyBundle("deny", """
                {
                  "id": "allow-report-read",
                  "description": "Allow report reads",
                  "decision": "allow",
                  "reason": "Report reads are safe for this bundle.",
                  "match": {
                    "tools": ["zap_report_read"],
                    "hosts": ["reports.example.com"]
                  }
                }
                """));
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setBundleFile(bundleFile.toString());

        PolicyEnforcementDecision decision = hook(properties).evaluate(new ToolExecutionPolicyContext(
                "zap_report_read",
                "https://reports.example.com",
                "corr-file"
        ));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.details())
                .containsEntry("policySource", "file")
                .containsEntry("policyBundleFileConfigured", true)
                .containsEntry("matchedRuleId", "allow-report-read");
    }

    private BasicPolicyBundleToolExecutionPolicyHook hook(PolicyEnforcementProperties properties) {
        return new BasicPolicyBundleToolExecutionPolicyHook(
                properties,
                new PolicyDryRunService(new ObjectMapper(), new ToolScopeRegistry())
        );
    }

    private String policyBundle(String defaultDecision, String ruleJson) {
        return """
                {
                  "apiVersion": "mcp.zap.policy/v1",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "basic-runtime-rules",
                    "description": "Basic runtime rules",
                    "owner": "security-platform"
                  },
                  "spec": {
                    "defaultDecision": "%s",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      %s
                    ]
                  }
                }
                """.formatted(defaultDecision, ruleJson);
    }
}
