package mcp.server.zap.core.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.observability.ObservabilityService;
import mcp.server.zap.core.service.authz.ToolScopeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PolicyDryRunDeterministicContractTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path FIXTURES_DIR = Path.of("src/test/resources/policy-dry-run-contracts");

    private ObservabilityService observabilityService;
    private PolicyDryRunService service;

    @BeforeEach
    void setUp() {
        observabilityService = mock(ObservabilityService.class);
        service = new PolicyDryRunService(OBJECT_MAPPER, new ToolScopeRegistry(), observabilityService);
    }

    @Test
    void allowContractMatchesSnapshotAndStaysStableAcrossReruns() throws Exception {
        String bundle = Files.readString(Path.of("examples/policy-bundles/ci-guided-guardrails.json"));
        JsonNode expected = fixture("allow-guided-ci-sandbox-hours.json");

        Map<String, Object> first = service.dryRun(
                bundle,
                "zap_attack_start",
                "https://api.sandbox.example.com/orders",
                "2026-06-02T01:00:00Z"
        );
        Map<String, Object> second = service.dryRun(
                bundle,
                "zap_attack_start",
                "https://api.sandbox.example.com/orders",
                "2026-06-02T01:00:00Z"
        );

        assertStableContract(first, second, expected, "allow");
    }

    @Test
    void defaultDenyContractMatchesSnapshotAndStaysStableAcrossReruns() throws Exception {
        String bundle = Files.readString(Path.of("examples/policy-bundles/expert-readonly-triage.json"));
        JsonNode expected = fixture("default-deny-expert-readonly-triage.json");

        Map<String, Object> first = service.dryRun(
                bundle,
                "zap_report_read",
                "https://prod.example.com",
                "2026-04-06T09:00:00Z"
        );
        Map<String, Object> second = service.dryRun(
                bundle,
                "zap_report_read",
                "https://prod.example.com",
                "2026-04-06T09:00:00Z"
        );

        assertStableContract(first, second, expected, "deny");
    }

    @Test
    void invalidContractMatchesSnapshotAndStaysStableAcrossReruns() throws Exception {
        JsonNode expected = fixture("invalid-policy-preview.json");
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

        Map<String, Object> first = service.dryRun(
                invalidBundle,
                "zap_unknown_tool",
                "://bad-target",
                "not-a-timestamp"
        );
        Map<String, Object> second = service.dryRun(
                invalidBundle,
                "zap_unknown_tool",
                "://bad-target",
                "not-a-timestamp"
        );

        assertStableContract(first, second, expected, "invalid");
    }

    private void assertStableContract(Map<String, Object> first,
                                      Map<String, Object> second,
                                      JsonNode expected,
                                      String expectedOutcome) throws Exception {
        JsonNode firstNode = OBJECT_MAPPER.valueToTree(first);
        JsonNode secondNode = OBJECT_MAPPER.valueToTree(second);

        assertThat(firstNode).isEqualTo(expected);
        assertThat(secondNode).isEqualTo(expected);
        assertThat(canonicalJson(firstNode)).isEqualTo(canonicalJson(secondNode));

        List<CapturedPolicyDecision> audits = capturedPolicyDecisions(2);
        assertThat(audits).extracting(CapturedPolicyDecision::outcome).containsExactly(expectedOutcome, expectedOutcome);
        assertThat(canonicalJson(audits.get(0).details())).isEqualTo(canonicalJson(audits.get(1).details()));
    }

    private JsonNode fixture(String fileName) throws Exception {
        return OBJECT_MAPPER.readTree(Files.readString(FIXTURES_DIR.resolve(fileName)));
    }

    private String canonicalJson(Object value) throws Exception {
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private List<CapturedPolicyDecision> capturedPolicyDecisions(int expectedCount) {
        ArgumentCaptor<String> outcomeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(observabilityService, times(expectedCount)).recordPolicyDecision(
                outcomeCaptor.capture(),
                detailsCaptor.capture(),
                correlationCaptor.capture()
        );

        List<CapturedPolicyDecision> decisions = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            decisions.add(new CapturedPolicyDecision(
                    outcomeCaptor.getAllValues().get(i),
                    detailsCaptor.getAllValues().get(i),
                    correlationCaptor.getAllValues().get(i)
            ));
        }
        return List.copyOf(decisions);
    }

    private record CapturedPolicyDecision(String outcome, Map<String, Object> details, String correlationId) {
    }
}
