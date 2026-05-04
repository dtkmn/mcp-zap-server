package mcp.server.zap.core.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.gateway.EngineApiImportAccess.UrlImportRequest;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationDiagnostics;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutationResult;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutationResult;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import mcp.server.zap.core.gateway.EngineReportAccess.ReportGenerationRequest;
import mcp.server.zap.core.gateway.EngineRuntimeAccess.NetworkDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.AjaxSpider;
import org.zaproxy.clientapi.gen.Authentication;
import org.zaproxy.clientapi.gen.Automation;
import org.zaproxy.clientapi.gen.Context;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Graphql;
import org.zaproxy.clientapi.gen.Network;
import org.zaproxy.clientapi.gen.Openapi;
import org.zaproxy.clientapi.gen.Pscan;
import org.zaproxy.clientapi.gen.Reports;
import org.zaproxy.clientapi.gen.Soap;
import org.zaproxy.clientapi.gen.Users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZapEngineBoundaryAccessTest {

    private org.zaproxy.clientapi.gen.Alert alert;
    private Reports reports;
    private Openapi openapi;
    private Graphql graphql;
    private Soap soap;
    private Context context;
    private Users users;
    private Authentication authentication;
    private Pscan pscan;
    private AjaxSpider ajaxSpider;
    private Automation automation;
    private Core core;
    private Network network;
    private ZapEngineFindingAccess findingAccess;
    private ZapEngineReportAccess reportAccess;
    private ZapEngineApiImportAccess importAccess;
    private ZapEngineContextAccess contextAccess;
    private ZapEnginePassiveScanAccess passiveScanAccess;
    private ZapEngineAjaxSpiderExecution ajaxSpiderExecution;
    private ZapEngineAutomationAccess automationAccess;
    private ZapEngineInventoryAccess inventoryAccess;
    private ZapEngineRuntimeAccess runtimeAccess;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        alert = mock(org.zaproxy.clientapi.gen.Alert.class);
        reports = mock(Reports.class);
        openapi = mock(Openapi.class);
        graphql = mock(Graphql.class);
        soap = mock(Soap.class);
        context = mock(Context.class);
        users = mock(Users.class);
        authentication = mock(Authentication.class);
        pscan = mock(Pscan.class);
        ajaxSpider = mock(AjaxSpider.class);
        automation = mock(Automation.class);
        core = mock(Core.class);
        network = mock(Network.class);
        clientApi.alert = alert;
        clientApi.reports = reports;
        clientApi.openapi = openapi;
        clientApi.graphql = graphql;
        clientApi.soap = soap;
        clientApi.context = context;
        clientApi.users = users;
        clientApi.authentication = authentication;
        clientApi.pscan = pscan;
        clientApi.ajaxSpider = ajaxSpider;
        clientApi.automation = automation;
        clientApi.core = core;
        clientApi.network = network;

        findingAccess = new ZapEngineFindingAccess(clientApi);
        reportAccess = new ZapEngineReportAccess(clientApi);
        importAccess = new ZapEngineApiImportAccess(clientApi);
        contextAccess = new ZapEngineContextAccess(clientApi);
        passiveScanAccess = new ZapEnginePassiveScanAccess(clientApi);
        ajaxSpiderExecution = new ZapEngineAjaxSpiderExecution(clientApi);
        automationAccess = new ZapEngineAutomationAccess(clientApi);
        inventoryAccess = new ZapEngineInventoryAccess(clientApi);
        runtimeAccess = new ZapEngineRuntimeAccess(clientApi);
    }

    @Test
    void findingsAdapterParsesZapAlertsIntoGatewaySnapshots() throws Exception {
        when(alert.alerts("http://target", "0", "-1", null, null, null))
                .thenReturn(new ApiResponseList("alerts", List.of(
                        alert("1", "40018", "SQL Injection", "High", "Medium", "http://target/a")
                )));

        List<AlertSnapshot> alerts = findingAccess.loadAlerts("http://target");

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().pluginId()).isEqualTo("40018");
        assertThat(alerts.getFirst().name()).isEqualTo("SQL Injection");
        assertThat(alerts.getFirst().risk()).isEqualTo("High");
    }

    @Test
    void reportAdapterCallsZapReportApiBehindBoundary() throws Exception {
        when(reports.generate("title", "traditional-json-plus", "", "", "", "site", "", "", "",
                "zap-report-1", "", "/tmp/reports", "false"))
                .thenReturn(new ApiResponseElement("file", "/tmp/reports/zap-report-1.json"));

        String reportPath = reportAccess.generateReport(new ReportGenerationRequest(
                "title",
                "traditional-json-plus",
                "",
                "",
                "",
                "site",
                "",
                "",
                "",
                "zap-report-1",
                "",
                "/tmp/reports",
                "false"
        ));

        assertThat(reportPath).isEqualTo("/tmp/reports/zap-report-1.json");
    }

    @Test
    void apiImportAdapterFlattensZapResponses() throws Exception {
        when(openapi.importUrl("http://example.com/api.yaml", "host"))
                .thenReturn(new ApiResponseList("imports", List.of(new ApiResponseElement("id", "9"))));

        EngineApiImportAccess.ImportResult result =
                importAccess.importOpenApiUrl(new UrlImportRequest("http://example.com/api.yaml", "host"));

        assertThat(result.values()).containsExactly("9");
    }

    @Test
    void contextAdapterCreatesContextAndAppliesScope() throws Exception {
        when(context.contextList()).thenReturn(new ApiResponseList("contextList"));
        when(context.context("api-auth")).thenReturn(set("context", Map.of(
                "id", element("id", "11"),
                "inScope", element("inScope", "true")
        )));
        when(context.includeRegexs("api-auth")).thenReturn(new ApiResponseList("includeRegexs",
                List.of(element("regex", "https://api.example.com/.*"))));
        when(context.excludeRegexs("api-auth")).thenReturn(new ApiResponseList("excludeRegexs",
                List.of(element("regex", "https://api.example.com/logout.*"))));

        ContextMutationResult result = contextAccess.upsertContext(new ContextMutation(
                "api-auth",
                List.of("https://api.example.com/.*"),
                List.of("https://api.example.com/logout.*"),
                true
        ));

        assertThat(result.created()).isTrue();
        assertThat(result.context().contextId()).isEqualTo("11");
        verify(context).newContext("api-auth");
        verify(context).setContextRegexs(
                "api-auth",
                "[\"https://api.example.com/.*\"]",
                "[\"https://api.example.com/logout.*\"]"
        );
        verify(context).setContextInScope("api-auth", "true");
    }

    @Test
    void contextAdapterUpdatesExistingUser() throws Exception {
        when(users.usersList("5")).thenReturn(new ApiResponseList("usersList", List.of(
                set("user", Map.of(
                        "id", element("id", "77"),
                        "name", element("name", "scan-user"),
                        "enabled", element("enabled", "false")
                ))
        )));
        when(users.getUserById("5", "77")).thenReturn(set("user", Map.of(
                "id", element("id", "77"),
                "name", element("name", "scan-user"),
                "enabled", element("enabled", "true")
        )));

        UserMutationResult result = contextAccess.upsertUser(new UserMutation(
                "5",
                "scan-user",
                "username=scan-user&password=s3cr3t",
                true
        ));

        assertThat(result.created()).isFalse();
        assertThat(result.userId()).isEqualTo("77");
        assertThat(result.enabled()).isTrue();
        verify(users).setAuthenticationCredentials("5", "77", "username=scan-user&password=s3cr3t");
        verify(users).setUserEnabled("5", "77", "true");
    }

    @Test
    void contextAdapterParsesAuthenticationDiagnostics() throws Exception {
        when(users.authenticateAsUser("1", "7")).thenReturn(set("auth", Map.of(
                "authSuccessful", element("authSuccessful", "true")
        )));
        when(users.getAuthenticationState("1", "7")).thenReturn(set("state", Map.of(
                "lastPollResult", element("lastPollResult", "true")
        )));

        AuthenticationDiagnostics diagnostics = contextAccess.testUserAuthentication("1", "7");

        assertThat(diagnostics.likelyAuthenticated()).isTrue();
        assertThat(diagnostics.authResponse()).contains("authSuccessful");
        assertThat(diagnostics.authState()).contains("lastPollResult");
    }

    @Test
    void passiveScanAdapterParsesBacklogSnapshot() throws Exception {
        when(pscan.recordsToScan()).thenReturn(element("recordsToScan", "4"));
        when(pscan.scanOnlyInScope()).thenReturn(element("scanOnlyInScope", "true"));
        when(pscan.currentTasks()).thenReturn(new ApiResponseList("tasks", List.of(
                element("task", "task-1"),
                element("task", "task-2")
        )));

        EnginePassiveScanAccess.PassiveScanSnapshot snapshot = passiveScanAccess.loadPassiveScanSnapshot();

        assertThat(snapshot.recordsToScan()).isEqualTo(4);
        assertThat(snapshot.activeTasks()).isEqualTo(2);
        assertThat(snapshot.scanOnlyInScope()).isTrue();
        assertThat(snapshot.completed()).isFalse();
    }

    @Test
    void ajaxSpiderAdapterStartsAndReadsBrowserSpiderStatus() throws Exception {
        when(ajaxSpider.optionMaxDuration()).thenReturn(element("optionMaxDuration", "5"));
        when(ajaxSpider.status()).thenReturn(element("status", "running"));
        when(ajaxSpider.numberOfResults()).thenReturn(element("numberOfResults", "12"));

        String scanId = ajaxSpiderExecution.startAjaxSpider(new EngineAjaxSpiderExecution.AjaxSpiderScanRequest("http://target"));
        EngineAjaxSpiderExecution.AjaxSpiderStatus status = ajaxSpiderExecution.readAjaxSpiderStatus();

        assertThat(scanId).startsWith("ajax-spider:");
        assertThat(status.running()).isTrue();
        assertThat(status.discoveredCount()).isEqualTo("12");
        verify(core).accessUrl("http://target", "true");
        verify(ajaxSpider).scan("http://target", "false", "", "");
    }

    @Test
    void automationAdapterParsesPlanProgress() throws Exception {
        when(automation.runPlan("/zap/automation/plan.yaml")).thenReturn(element("planId", "33"));
        when(automation.planProgress("33")).thenReturn(set("planProgress", Map.of(
                "started", element("started", "2026-05-03T00:00:00Z"),
                "finished", element("finished", "2026-05-03T00:00:10Z"),
                "info", new ApiResponseList("info", List.of(element("info", "requestor finished"))),
                "warn", new ApiResponseList("warn", List.of()),
                "error", new ApiResponseList("error", List.of())
        )));

        String planId = automationAccess.runAutomationPlan("/zap/automation/plan.yaml");
        EngineAutomationAccess.AutomationPlanProgress progress = automationAccess.loadAutomationPlanProgress(planId);

        assertThat(planId).isEqualTo("33");
        assertThat(progress.completed()).isTrue();
        assertThat(progress.successful()).isTrue();
        assertThat(progress.info()).containsExactly("requestor finished");
    }

    @Test
    void inventoryAdapterParsesCoreAlertsAndSites() throws Exception {
        when(core.alerts("http://target", "0", "-1")).thenReturn(new ApiResponseList("alerts", List.of(
                set("alert", Map.of(
                        "alert", element("alert", "Missing Header"),
                        "risk", element("risk", "Low"),
                        "url", element("url", "http://target/")
                ))
        )));
        when(core.hosts()).thenReturn(new ApiResponseList("hosts", List.of(element("host", "target"))));
        when(core.sites()).thenReturn(new ApiResponseList("sites", List.of(element("site", "http://target/"))));
        when(core.urls("http://target")).thenReturn(new ApiResponseList("urls", List.of(element("url", "http://target/a"))));

        assertThat(inventoryAccess.loadAlertSummaries("http://target").getFirst().name()).isEqualTo("Missing Header");
        assertThat(inventoryAccess.listHosts()).containsExactly("target");
        assertThat(inventoryAccess.listSites()).containsExactly("http://target/");
        assertThat(inventoryAccess.listUrls("http://target")).containsExactly("http://target/a");
    }

    @Test
    void runtimeAdapterReadsVersionAndAppliesNetworkDefaults() throws Exception {
        when(core.version()).thenReturn(element("version", "2.17.0"));

        String version = runtimeAccess.readVersion();
        runtimeAccess.applyNetworkDefaults(new NetworkDefaults("Agentic Security Gateway", 30, 60));

        assertThat(version).isEqualTo("2.17.0");
        verify(network).setDefaultUserAgent("Agentic Security Gateway");
        verify(network).setConnectionTimeout("30");
        verify(network).setDnsTtlSuccessfulQueries("60");
    }

    private ApiResponseSet alert(String id,
                                 String pluginId,
                                 String name,
                                 String risk,
                                 String confidence,
                                 String url) {
        return set("alert", Map.ofEntries(
                Map.entry("id", element("id", id)),
                Map.entry("pluginId", element("pluginId", pluginId)),
                Map.entry("name", element("name", name)),
                Map.entry("description", element("description", name + " description")),
                Map.entry("risk", element("risk", risk)),
                Map.entry("confidence", element("confidence", confidence)),
                Map.entry("url", element("url", url)),
                Map.entry("param", element("param", "id")),
                Map.entry("attack", element("attack", "attack payload")),
                Map.entry("evidence", element("evidence", "evidence sample")),
                Map.entry("reference", element("reference", "https://example.com/reference")),
                Map.entry("solution", element("solution", "Apply a fix")),
                Map.entry("messageId", element("messageId", "101")),
                Map.entry("cweid", element("cweid", "89")),
                Map.entry("wascid", element("wascid", "19"))
        ));
    }

    private ApiResponseElement element(String name, String value) {
        return new ApiResponseElement(name, value);
    }

    private ApiResponseSet set(String name, Map<String, ApiResponse> values) {
        return new ApiResponseSet(name, new LinkedHashMap<>(values));
    }
}
