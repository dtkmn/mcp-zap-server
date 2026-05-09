package mcp.server.zap.core.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.extension.api.policy.PolicyBundleAccessBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PolicyDryRunServiceTest {

    private ObservabilityService observabilityService;
    private PolicyDryRunService service;

    @BeforeEach
    void setUp() {
        observabilityService = mock(ObservabilityService.class);
        service = new PolicyDryRunService(new ObjectMapper(), new ToolScopeRegistry(), observabilityService);
    }

    @Test
    void dryRunMatchesAllowRuleForSandboxGuidedRequest() throws Exception {
        String bundle = Files.readString(Path.of("examples/policy-bundles/ci-guided-guardrails.json"));

        Map<String, Object> response = service.dryRun(
                bundle,
                "zap_attack_start",
                "https://api.sandbox.example.com/orders",
                "2026-06-02T01:00:00Z"
        );

        assertThat(response).containsEntry("contractVersion", PolicyDryRunService.DRY_RUN_CONTRACT_VERSION);
        assertThat(validation(response)).containsEntry("valid", true);
        assertThat(decision(response)).containsEntry("result", "allow");
        assertThat(decision(response)).containsEntry("source", "rule");
        assertThat(decision(response)).containsEntry("matchedRuleId", "allow-guided-ci-sandbox-hours");
        assertThat(request(response)).containsEntry("normalizedHost", "api.sandbox.example.com");
        assertThat(request(response)).containsEntry("bundleLocalDay", "tue");

        CapturedPolicyDecision audit = capturedPolicyDecision();
        assertThat(audit.outcome()).isEqualTo("allow");
        assertThat(audit.correlationId()).isNull();
        assertThat(audit.details())
                .containsEntry("bundleName", "ci-guided-guardrails")
                .containsEntry("bundleOwner", "security-platform")
                .containsEntry("evaluatedTool", "zap_attack_start")
                .containsEntry("normalizedHost", "api.sandbox.example.com")
                .containsEntry("decisionSource", "rule")
                .containsEntry("matchedRuleId", "allow-guided-ci-sandbox-hours")
                .containsEntry("reason", "Sandbox CI is approved during staffed rollout hours.")
                .containsEntry("validationValid", true);
        assertThat(traceSummary(audit))
                .extracting(entry -> entry.get("ruleId"))
                .contains("allow-guided-ci-sandbox-hours");
    }

    @Test
    void dryRunFallsBackToDefaultDecisionWhenNoRuleMatches() throws Exception {
        String bundle = Files.readString(Path.of("examples/policy-bundles/expert-readonly-triage.json"));

        Map<String, Object> response = service.dryRun(
                bundle,
                "zap_report_read",
                "https://prod.example.com",
                "2026-04-06T09:00:00Z"
        );

        assertThat(validation(response)).containsEntry("valid", true);
        assertThat(decision(response)).containsEntry("result", "deny");
        assertThat(decision(response)).containsEntry("source", "default");
        assertThat((List<Map<String, Object>>) response.get("trace"))
                .extracting(entry -> entry.get("ruleId"))
                .contains("allow-readonly-review-surface", "deny-expert-mutation-tools");

        CapturedPolicyDecision audit = capturedPolicyDecision();
        assertThat(audit.outcome()).isEqualTo("deny");
        assertThat(audit.details())
                .containsEntry("bundleName", "expert-readonly-triage")
                .containsEntry("evaluatedTool", "zap_report_read")
                .containsEntry("normalizedHost", "prod.example.com")
                .containsEntry("decisionSource", "default")
                .containsEntry("defaultDecision", "deny")
                .containsEntry("validationValid", true)
                .doesNotContainKey("matchedRuleId");
    }

    @Test
    void dryRunSurfacesValidationErrorsForUnknownToolsAndInvalidRequestInputs() {
        String invalidBundle = """
                {
                  "apiVersion": "mcp.zap.policy/v1",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "broken-bundle",
                    "description": "broken",
                    "owner": "security"
                  },
                  "spec": {
                    "defaultDecision": "deny",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      {
                        "id": "broken-rule",
                        "description": "broken",
                        "decision": "allow",
                        "reason": "broken",
                        "match": {
                          "tools": ["zap:not:real"]
                        }
                      }
                    ]
                  }
                }
                """;

        Map<String, Object> response = service.dryRun(
                invalidBundle,
                "zap_unknown_tool",
                "://bad-target",
                "not-a-timestamp"
        );

        assertThat(validation(response)).containsEntry("valid", false);
        assertThat((List<String>) validation(response).get("errors"))
                .anyMatch(error -> error.contains("toolName must be an exact known MCP tool or action"))
                .anyMatch(error -> error.contains("target must be"))
                .anyMatch(error -> error.contains("evaluatedAt must be"))
                .anyMatch(error -> error.contains("unknown tool 'zap:not:real'"));
        assertThat(decision(response)).containsEntry("result", "invalid");
        assertThat(response).containsEntry("trace", List.of());

        CapturedPolicyDecision audit = capturedPolicyDecision();
        assertThat(audit.outcome()).isEqualTo("invalid");
        assertThat(audit.details())
                .containsEntry("decisionSource", "validation")
                .containsEntry("validationValid", false)
                .containsEntry("reason", "The request or policy bundle is invalid.");
        assertThat((List<String>) audit.details().get("validationErrors"))
                .anyMatch(error -> error.contains("toolName must be an exact known MCP tool or action"))
                .anyMatch(error -> error.contains("target must be"))
                .anyMatch(error -> error.contains("evaluatedAt must be"));
    }

    @Test
    void dryRunRejectsBundlesOutsideCurrentTenantScope() {
        service.setPolicyBundleAccessBoundary(new PolicyBundleAccessBoundary() {
            @Override
            public List<String> validateCurrentRequesterAccess(Map<String, String> bundleLabels) {
                return List.of("bundle.metadata.labels.tenant must match the current tenant scope");
            }

            @Override
            public void enrichBundleSummary(Map<String, Object> bundleSummary, Map<String, String> bundleLabels) {
                bundleSummary.put("tenant", bundleLabels.get("tenant"));
            }

            @Override
            public void enrichRequestSummary(Map<String, Object> requestSummary) {
                requestSummary.put("tenant", "tenant-beta");
                requestSummary.put("workspace", "workspace-three");
            }
        });

        String bundle = """
                {
                  "apiVersion": "mcp.zap.policy/v1",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "tenant-scoped-bundle",
                    "description": "tenant scoped",
                    "owner": "security",
                    "labels": {
                      "tenant": "tenant-alpha"
                    }
                  },
                  "spec": {
                    "defaultDecision": "deny",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      {
                        "id": "allow-sandbox",
                        "description": "allow sandbox",
                        "decision": "allow",
                        "reason": "safe sandbox",
                        "match": {
                          "tools": ["zap_report_read"],
                          "hosts": ["prod.example.com"]
                        }
                      }
                    ]
                  }
                }
                """;

        Map<String, Object> response = service.dryRun(
                bundle,
                "zap_report_read",
                "https://prod.example.com",
                "2026-04-06T09:00:00Z"
        );

        assertThat(validation(response)).containsEntry("valid", false);
        assertThat(decision(response)).containsEntry("result", "invalid");
        assertThat((List<String>) validation(response).get("errors"))
                .contains("bundle.metadata.labels.tenant must match the current tenant scope");
        assertThat(request(response))
                .containsEntry("tenant", "tenant-beta")
                .containsEntry("workspace", "workspace-three");
        assertThat((Map<String, Object>) response.get("bundle"))
                .containsEntry("tenant", "tenant-alpha");

        CapturedPolicyDecision audit = capturedPolicyDecision();
        assertThat(audit.outcome()).isEqualTo("invalid");
        assertThat(audit.details())
                .containsEntry("bundleTenant", "tenant-alpha")
                .containsEntry("requestTenant", "tenant-beta")
                .containsEntry("requestWorkspace", "workspace-three");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validation(Map<String, Object> response) {
        return (Map<String, Object>) response.get("validation");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decision(Map<String, Object> response) {
        return (Map<String, Object>) response.get("decision");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(Map<String, Object> response) {
        return (Map<String, Object>) response.get("request");
    }

    @SuppressWarnings("unchecked")
    private CapturedPolicyDecision capturedPolicyDecision() {
        ArgumentCaptor<String> outcomeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(observabilityService).recordPolicyDecision(outcomeCaptor.capture(), detailsCaptor.capture(), correlationCaptor.capture());
        return new CapturedPolicyDecision(outcomeCaptor.getValue(), detailsCaptor.getValue(), correlationCaptor.getValue());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> traceSummary(CapturedPolicyDecision audit) {
        return (List<Map<String, Object>>) audit.details().get("traceSummary");
    }

    private record CapturedPolicyDecision(String outcome, Map<String, Object> details, String correlationId) {
    }
}
