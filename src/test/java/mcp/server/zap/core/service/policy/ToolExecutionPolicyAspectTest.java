package mcp.server.zap.core.service.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Stream;
import mcp.server.zap.core.configuration.PolicyEnforcementProperties;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import mcp.server.zap.extension.api.policy.ToolExecutionPolicyHook;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.CodeSignature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionPolicyAspectTest {

    @Test
    void hostlessReportReadArgumentsCanBeAllowedByRuntimePolicy() throws Throwable {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        properties.setBundle("""
                {
                  "apiVersion": "mcp.zap.policy/v1",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "hostless-followup-policy",
                    "description": "Allow guided hostless follow-up calls.",
                    "owner": "security-platform"
                  },
                  "spec": {
                    "defaultDecision": "deny",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      {
                        "id": "allow-hostless-report-read",
                        "description": "Allow report readback without a target parameter.",
                        "decision": "allow",
                        "reason": "Report readback is approved as a guided follow-up.",
                        "match": {
                          "tools": ["zap_report_read"]
                        }
                      }
                    ]
                  }
                }
                """);
        ObservabilityService observabilityService = mock(ObservabilityService.class);
        PolicyDryRunService dryRunService = new PolicyDryRunService(new ObjectMapper(), new ToolScopeRegistry(), null);
        BasicPolicyBundleToolExecutionPolicyHook hook = new BasicPolicyBundleToolExecutionPolicyHook(properties, dryRunService);
        ToolExecutionPolicyService policyService = new ToolExecutionPolicyService(
                properties,
                policyHooks(hook),
                observabilityService
        );
        ToolExecutionPolicyAspect aspect = new ToolExecutionPolicyAspect(policyService);

        Object response = aspect.enforcePolicy(
                joinPointFor(
                        "readReport",
                        new String[]{"reportPath", "maxChars"},
                        new Object[]{"/zap/wrk/report.html", 1000}
                ),
                toolAnnotation("readReport", String.class, Integer.class)
        );

        assertThat(response).isEqualTo("tool-result");
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(observabilityService).recordPolicyDecision(
                org.mockito.ArgumentMatchers.eq("allow"),
                detailsCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(detailsCaptor.getValue())
                .containsEntry("tool", "zap_report_read")
                .containsEntry("allowed", true)
                .containsEntry("matchedRuleId", "allow-hostless-report-read")
                .containsEntry("decisionResult", "allow")
                .doesNotContainKey("targetProvided")
                .doesNotContainKey("normalizedHost");
    }

    @Test
    void hostlessPassiveStatusArgumentsCanBeAllowedByRuntimePolicy() throws Throwable {
        PolicyEnforcementProperties properties = new PolicyEnforcementProperties();
        properties.setMode(PolicyEnforcementProperties.Mode.ENFORCE);
        properties.setBundle("""
                {
                  "apiVersion": "mcp.zap.policy/v1",
                  "kind": "PolicyBundle",
                  "metadata": {
                    "name": "hostless-followup-policy",
                    "description": "Allow guided hostless follow-up calls.",
                    "owner": "security-platform"
                  },
                  "spec": {
                    "defaultDecision": "deny",
                    "evaluationOrder": "first-match",
                    "timezone": "UTC",
                    "rules": [
                      {
                        "id": "allow-hostless-passive-status",
                        "description": "Allow passive status without a target parameter.",
                        "decision": "allow",
                        "reason": "Passive status is approved as a guided follow-up.",
                        "match": {
                          "tools": ["zap_passive_scan_status"]
                        }
                      }
                    ]
                  }
                }
                """);
        ObservabilityService observabilityService = mock(ObservabilityService.class);
        PolicyDryRunService dryRunService = new PolicyDryRunService(new ObjectMapper(), new ToolScopeRegistry(), null);
        BasicPolicyBundleToolExecutionPolicyHook hook = new BasicPolicyBundleToolExecutionPolicyHook(properties, dryRunService);
        ToolExecutionPolicyService policyService = new ToolExecutionPolicyService(
                properties,
                policyHooks(hook),
                observabilityService
        );
        ToolExecutionPolicyAspect aspect = new ToolExecutionPolicyAspect(policyService);

        Object response = aspect.enforcePolicy(
                joinPointFor("passiveStatus", new String[]{}, new Object[]{}),
                toolAnnotation("passiveStatus")
        );

        assertThat(response).isEqualTo("tool-result");
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(observabilityService).recordPolicyDecision(
                org.mockito.ArgumentMatchers.eq("allow"),
                detailsCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(detailsCaptor.getValue())
                .containsEntry("tool", "zap_passive_scan_status")
                .containsEntry("allowed", true)
                .containsEntry("matchedRuleId", "allow-hostless-passive-status")
                .containsEntry("decisionResult", "allow")
                .doesNotContainKey("targetProvided")
                .doesNotContainKey("normalizedHost");
    }

    private ProceedingJoinPoint joinPointFor(String methodName, String[] parameterNames, Object[] args) throws Throwable {
        CodeSignature signature = mock(CodeSignature.class);
        when(signature.getName()).thenReturn(methodName);
        when(signature.getParameterNames()).thenReturn(parameterNames);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn("tool-result");
        return joinPoint;
    }

    private Tool toolAnnotation(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = SampleToolMethods.class.getDeclaredMethod(methodName, parameterTypes);
        return method.getAnnotation(Tool.class);
    }

    private ObjectProvider<ToolExecutionPolicyHook> policyHooks(ToolExecutionPolicyHook hook) {
        ObjectProvider<ToolExecutionPolicyHook> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(hook));
        return provider;
    }

    private static class SampleToolMethods {
        @Tool(name = "zap_report_read")
        String readReport(String reportPath, Integer maxChars) {
            return reportPath + ":" + maxChars;
        }

        @Tool(name = "zap_passive_scan_status")
        String passiveStatus() {
            return "unused";
        }
    }
}
