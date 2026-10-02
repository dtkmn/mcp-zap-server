package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineAutomationAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AutomationPlanTargetPolicyTest {
    private static final String URL = "http://93.184.216.34/";
    private static final String ENV = "env: {contexts: [{name: target, urls: ['" + URL + "']}] }\n";
    @TempDir Path root;
    private EngineAutomationAccess engine;
    private UrlValidationService policy;
    private AutomationPlanService service;

    @BeforeEach
    void setup() {
        engine = mock(EngineAutomationAccess.class);
        policy = new UrlValidationService();
        ReflectionTestUtils.setField(policy, "whitelist", List.of());
        ReflectionTestUtils.setField(policy, "blacklist", List.of("localhost", "127.0.0.1", "0.0.0.0"));
        service = new AutomationPlanService(engine, policy);
        ReflectionTestUtils.setField(service, "automationLocalDirectory", root.toString());
        ReflectionTestUtils.setField(service, "automationZapDirectory", "/zap/automation");
        when(engine.runAutomationPlan(anyString())).thenReturn("7");
    }

    @ParameterizedTest
    @MethodSource("unsafePlans")
    void rejectsUnsafeEffectivePlansForInlineAndFileInputs(String yaml) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null, yaml, "inline.yaml"));
        Path file = root.resolve("source.yaml");
        Files.writeString(file, yaml);
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(file.toString(), null, null));
        verifyNoInteractions(engine);
        try (var files = Files.walk(root.resolve("runs"))) {
            assertFalse(files.anyMatch(Files::isRegularFile), "Rejected plans must not be materialized");
        }
    }

    static Stream<String> unsafePlans() {
        return Stream.of(
                "env: {contexts: [{name: bad, urls: ['http://127.0.0.1/']}] }\njobs: []",
                "env: {contexts: [{name: good, urls: ['" + URL + "']}, {name: bad, urls: ['http://10.1.2.3/']}] }",
                "env: {contexts: [{name: target, urls: ['" + URL + "'], url: 'http://127.0.0.1/'}] }",
                "env: {contexts: [{name: target, urls: ['${DESTINATION}']}] }",
                "env: {contexts: [{name: '${CONTEXT}', urls: ['" + URL + "']}] }",
                "env: {contexts: [{name: target, urls: ['" + URL + "'], includePaths: ['.*']}] }",
                "env: {contexts: [{name: target, urls: ['" + URL + "']}], proxy: {hostname: localhost, port: 8080} }",
                "env: {contexts: [{name: target, urls: ['" + URL + "']}], configs: {network.connection.httpProxy.host: localhost} }",
                "env: {contexts: [{name: target, urls: ['" + URL + "']}, {name: target, urls: ['" + URL + "']}] }",
                "env: {contexts: [{name: target, urls: ['" + URL + "']}, ignored] }",
                "env: {contexts: [{name: target, urls: '" + URL + "'}] }",
                "env: {contexts: [{name: target, urls: [42]}] }",
                "env: {contexts: [{name: target, urls: []}] }",
                "env: {contexts: [{name: target, urls: ['http://user@93.184.216.34/']}] }",
                "env: {contexts: [{name: target, urls: ['http://93.184.216.34:65536/']}] }",
                "env: {contexts: [{name: target, urls: ['http://93.184.216.34/#fragment']}] }",
                ENV + "jobs: [ignored]",
                ENV + "jobs: {type: requestor}",
                ENV + "jobs: [{type: requestor, requests: [ignored]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: 'http://169.254.169.254/'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: 'http://[::1]/'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: 'file:///etc/passwd'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: '${DESTINATION}'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: '" + URL + "', Url: 'http://127.0.0.1/'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{Url: 'http://127.0.0.1/'}]}]",
                ENV + "jobs: [{type: requestor, requests: [{url: null}]}]",
                ENV + "jobs: [{type: requestor, requests: {url: '" + URL + "'}}]",
                ENV + "jobs: [{type: spider, parameters: {url: 'http://10.1.2.3/'}}]",
                ENV + "jobs: [{type: activeScan, parameters: {url: 'http://127.0.0.1/'}}]",
                ENV + "jobs: [{type: spider, parameters: {url: '" + URL + "', Url: 'http://127.0.0.1/'}}]",
                ENV + "jobs: [{type: activeScan, parameters: {Url: 'http://127.0.0.1/'}}]",
                ENV + "jobs: [{type: spider, parameters: {url: '${DESTINATION}'}}]",
                ENV + "jobs: [{type: spider, parameters: {context: existing-engine-context}}]",
                ENV + "jobs: [{type: spider, parameters: {Context: target}}]",
                ENV + "jobs: [{type: spider, parameters: {context: '${CONTEXT}'}}]",
                ENV + "jobs: [{type: requestor, parameters: bad, requests: []}]",
                contextAuth("{method: script, parameters: {scriptInline: 'arbitrary code'}}"),
                contextAuth("{Method: script, Parameters: {scriptInline: 'arbitrary code'}}"),
                contextAuth("{method: browser, parameters: {loginPageUrl: '" + URL + "'}}"),
                contextAuth("{method: autodetect}"),
                contextAuth("{method: form, parameters: {loginRequestUrl: 'http://127.0.0.1/'}}"),
                contextAuth("{method: json, parameters: {loginRequestUrl: '" + URL + "', loginPageUrl: 'http://10.1.2.3/'}}"),
                contextAuth("{method: form, parameters: {loginRequestUrl: '${LOGIN}'}}"),
                contextAuth("{method: manual, verification: {method: poll, pollUrl: 'http://127.0.0.1/'}}"),
                contextAuth("{method: manual, verification: {method: poll, pollUrl: '${POLL}'}}"),
                contextAuth("{method: manual, Verification: {Method: poll, PollUrl: 'http://127.0.0.1/'}}"),
                contextAuth("{method: manual, scriptInline: 'arbitrary code'}"),
                contextSession("{method: script, parameters: {script: arbitrary.js}}"),
                contextSession("{Method: script, Parameters: {script: arbitrary.js}}"),
                contextSession("{method: cookie, script: arbitrary.js}"),
                ENV + "defaults: &params {Url: 'http://127.0.0.1/'}\njobs: [{type: spider, parameters: {<<: *params, url: '" + URL + "'}}]",
                ENV + "requests: &requests [{url: 'http://169.254.169.254/'}]\njobs: [{type: requestor, requests: *requests}]",
                ENV + "jobs: [{type: requestor, requests: [{url: '" + URL + "', url: 'http://127.0.0.1/'}]}]"
        );
    }

    private static String contextAuth(String auth) {
        return "env: {contexts: [{name: target, urls: ['" + URL + "'], authentication: " + auth + "}] }\n";
    }

    private static String contextSession(String session) {
        return "env: {contexts: [{name: target, urls: ['" + URL + "'], sessionManagement: " + session + "}] }\n";
    }

    @ParameterizedTest
    @ValueSource(strings = {"script", "openapi", "graphql", "soap", "replacer", "addOns", "spiderAjax", "spiderClient", "import", "sequence-import", "unknown"})
    void rejectsUnsupportedJobsEvenWhenDisabled(String type) {
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null,
                ENV + "jobs: [{type: '" + type + "', enabled: false}]", "plan.yaml"));
        verifyNoInteractions(engine);
    }

    @Test
    void configuredAllowlistAndBlacklistApplyToEveryDeclaredDestination() {
        ReflectionTestUtils.setField(policy, "whitelist", List.of("93.184.216.34"));
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null,
                ENV + "jobs: [{type: requestor, requests: [{url: 'http://203.0.113.10/'}]}]", "plan.yaml"));
        ReflectionTestUtils.setField(policy, "blacklist", List.of("93.184.216.34"));
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null, ENV, "plan.yaml"));
        verifyNoInteractions(engine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestor", "spider", "activeScan", "passiveScan-config", "passiveScan-wait", "activeScan-config", "activeScan-policy", "report", "exitStatus", "delay"})
    void supportedJobsReachEngineWithoutChangingDestinations(String type) throws Exception {
        String job = "jobs: [{type: '" + type + "', parameters: {context: target}"
                + ("requestor".equals(type) ? ", requests: [{url: '" + URL + "', data: '${BODY}'}]" : "") + "}]\n";
        assertTrue(service.runAutomationPlan(null, ENV + job, "plan.yaml").contains("Plan ID: 7"));
        verify(engine).runAutomationPlan(anyString());
        Path plan;
        try (var files = Files.walk(root.resolve("runs"))) {
            plan = files.filter(path -> path.toString().endsWith("plan.yaml")).findFirst().orElseThrow();
        }
        Map<?, ?> normalized = new Yaml().load(Files.readString(plan));
        Map<?, ?> env = (Map<?, ?>) normalized.get("env");
        Map<?, ?> context = (Map<?, ?>) ((List<?>) env.get("contexts")).getFirst();
        assertEquals(List.of(URL), context.get("urls"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"form", "json"})
    void literalLoginAndPollUrlsPermitCredentialVariablesAndCookieSessions(String method) {
        String plan = "env:\n  vars: {PASSWORD: test-value}\n  contexts:\n    - name: target\n      urls: ['" + URL + "']\n"
                + "      authentication:\n        method: " + method + "\n        parameters:\n"
                + "          loginRequestUrl: '" + URL + "login'\n          loginPageUrl: '" + URL + "login-page'\n"
                + "          loginRequestBody: 'password=${PASSWORD}'\n"
                + "        verification: {method: poll, pollUrl: '" + URL + "status'}\n"
                + "      sessionManagement: {method: cookie}\n"
                + "      users: [{name: user, credentials: {username: test, password: '${PASSWORD}'}}]\n"
                + "jobs: [{type: requestor, requests: [{url: '" + URL + "'}]}]\n";
        assertTrue(service.runAutomationPlan(null, plan, "authenticated.yaml").contains("Plan ID: 7"));
        verify(engine).runAutomationPlan(anyString());
    }

    @Test
    void explicitlyPermittedPrivateTargetsAreUsable() {
        ReflectionTestUtils.setField(policy, "allowPrivateNetworks", true);
        ReflectionTestUtils.setField(policy, "whitelist", List.of("10.1.2.3"));
        assertTrue(service.runAutomationPlan(null,
                "env: {contexts: [{name: private, urls: ['http://10.1.2.3/']}] }\njobs: []", "plan.yaml").contains("Plan ID: 7"));
        verify(engine).runAutomationPlan(anyString());
    }

    @Test
    void disablingUrlPolicyDoesNotEnableUnconstrainedPlanForms() {
        ReflectionTestUtils.setField(policy, "validationEnabled", false);
        assertTrue(service.runAutomationPlan(null,
                "env: {contexts: [{name: private, urls: ['http://10.1.2.3/']}] }\njobs: []", "plan.yaml").contains("Plan ID: 7"));
        clearInvocations(engine);
        assertThrows(IllegalArgumentException.class, () -> service.runAutomationPlan(null,
                ENV + "jobs: [{type: script}]", "plan.yaml"));
        verifyNoInteractions(engine);
    }
}
