package mcp.server.zap.core.configuration;

import java.util.List;
import java.util.Map;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AutomationPlanService;
import mcp.server.zap.core.service.FindingsService;
import mcp.server.zap.core.service.OpenApiService;
import mcp.server.zap.core.service.ReportService;
import mcp.server.zap.core.service.SpiderScanService;
import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mcp.server.auth.apiKeys[0].key=restricted-scope-integration-key",
        "mcp.server.auth.apiKeys[0].clientId=restricted-scope-client",
        "mcp.server.auth.apiKeys[0].scopes=mcp:tools:list,zap:scan:read,zap:scan:crawl:run,zap:scan:stop,zap:alerts:read,zap:report:read,zap:report:generate",
        "ZAP_API_KEY=quickstart-engine-test-key",
        "mcp.server.security.mode=api-key",
        "mcp.server.security.enabled=true",
        "mcp.server.security.authorization.mode=enforce",
        // Expert mode must not silently expand the credential's permissions.
        "mcp.server.tools.surface=expert",
        "mcp.server.protection.enabled=false"
})
@Import(AbstractMcpProtectionIntegrationTest.MockZapConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuickStartScopeAuthorizationIntegrationTest extends AbstractMcpProtectionIntegrationTest {
    private static final String KEY = "restricted-scope-integration-key";
    private static final String TARGET = "https://example.com";

    @Autowired
    private ApiKeyProperties apiKeys;

    @MockitoBean
    private EnginePassiveScanAccess passiveScanAccess;
    @MockitoBean
    private SpiderScanService spiderScanService;
    @MockitoBean
    private ActiveScanService activeScanService;
    @MockitoBean
    private OpenApiService openApiService;
    @MockitoBean
    private AutomationPlanService automationPlanService;
    @MockitoBean
    private GuidedAuthSessionService authSessionService;
    @MockitoBean
    private FindingsService findingsService;
    @MockitoBean
    private ReportService reportService;

    @BeforeEach
    void configureEngineDoubles() {
        assertThat(apiKeys.getApiKeys().getFirst().getScopes()).containsExactly(
                "mcp:tools:list", "zap:scan:read", "zap:scan:crawl:run", "zap:scan:stop",
                "zap:alerts:read", "zap:report:read", "zap:report:generate");
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenReturn(new PassiveScanSnapshot(0, 0, false));
        when(spiderScanService.startSpiderScan(TARGET)).thenReturn("Scan ID: restricted-crawl");
        when(spiderScanService.getSpiderScanStatus("restricted-crawl"))
                .thenReturn("Progress: 100%\nCompleted: yes");
        when(findingsService.getFindingsSummary(TARGET)).thenReturn("No findings in fixture.");
        when(reportService.generateReport("traditional-html-plus", "light", TARGET))
                .thenReturn("/zap/wrk/restricted-report.html");
        when(reportService.readReport("/zap/wrk/restricted-report.html", null))
                .thenReturn("Complete fixture report.");
    }

    @Test
    void explicitlyRestrictedClientCanCrawlAndReadReportsWithoutAttackRecommendations() throws Exception {
        String session = initializeSession(KEY);
        assertThat(listTools(KEY, session).getStatus().value()).isEqualTo(200);
        assertThat(text(callTool(KEY, session, "zap_passive_scan_status", Map.of())))
                .contains("Completed: yes");

        String started = text(callTool(KEY, session, "zap_crawl_start",
                Map.of("targetUrl", TARGET, "strategy", "http")));
        assertThat(started).contains("zap_crawl_status", "zap_passive_scan_wait")
                .doesNotContain("zap_attack_start");
        String operation = started.lines().filter(line -> line.startsWith("Operation ID: "))
                .findFirst().orElseThrow().substring("Operation ID: ".length());
        String completed = text(callTool(KEY, session, "zap_crawl_status", Map.of("operationId", operation)));
        assertThat(completed).contains("zap_passive_scan_wait").doesNotContain("zap_attack_start");

        for (var call : List.of(
                Map.entry("zap_passive_scan_wait", Map.<String, Object>of()),
                Map.entry("zap_findings_summary", Map.<String, Object>of("baseUrl", TARGET)),
                Map.entry("zap_report_generate", Map.<String, Object>of("baseUrl", TARGET)),
                Map.entry("zap_report_read", Map.<String, Object>of("reportPath", "/zap/wrk/restricted-report.html")))) {
            assertThat(text(callTool(KEY, session, call.getKey(), call.getValue()))).isNotBlank();
        }
        verifyNoInteractions(activeScanService, openApiService, automationPlanService, authSessionService);
    }

    @ParameterizedTest
    @CsvSource({
            "zap_attack_start,zap:scan:attack:run",
            "zap_active_scan_start,zap:scan:active:run",
            "zap_queue_active_scan,zap:scan:active:run",
            "zap_auth_session_prepare,zap:auth:session:write",
            "zap_auth_session_validate,zap:auth:test",
            "zap_target_import,zap:api:import",
            "zap_automation_plan_run,zap:automation:run",
            "zap_scan_policy_rule_set,zap:scan:policy:write",
            "zap_context_upsert,zap:context:write",
            "zap_user_upsert,zap:user:write"
    })
    void highImpactToolsAreDeniedBeforeDispatchEvenOnExpertSurface(String tool, String scope) throws Exception {
        EntityExchangeResult<String> result = callTool(KEY, initializeSession(KEY), tool, Map.of());

        assertThat(result.getStatus().value()).isEqualTo(403);
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponseBody());
        assertThat(body.path("error").asString()).isEqualTo("insufficient_scope");
        assertThat(body.path("requiredScopes").path(0).asString()).isEqualTo(scope);
        verifyNoInteractions(activeScanService, openApiService, automationPlanService, authSessionService,
                passiveScanAccess, spiderScanService, findingsService, reportService);
    }

    private String text(EntityExchangeResult<String> response) throws Exception {
        assertThat(response.getStatus().value()).isEqualTo(200);
        String body = response.getResponseBody();
        assertThat(body).isNotBlank();
        if (body.startsWith("event:") || body.startsWith("data:")) {
            body = body.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).stripLeading())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
        JsonNode result = OBJECT_MAPPER.readTree(body).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        String value = result.path("content").path(0).path("text").asString();
        // Method callbacks can wrap a String return value as a JSON string.
        return value.startsWith("\"") ? OBJECT_MAPPER.readTree(value).asString() : value;
    }
}
