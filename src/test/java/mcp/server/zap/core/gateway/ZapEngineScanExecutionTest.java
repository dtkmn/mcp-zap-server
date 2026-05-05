package mcp.server.zap.core.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.ActiveScanRuleMutation;
import mcp.server.zap.core.gateway.EngineScanExecution.ScannerRuleSnapshot;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.gen.Ascan;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Spider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZapEngineScanExecutionTest {

    private Core core;
    private Spider spider;
    private Ascan ascan;
    private ZapEngineScanExecution execution;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        core = mock(Core.class);
        spider = mock(Spider.class);
        ascan = mock(Ascan.class);
        clientApi.core = core;
        clientApi.spider = spider;
        clientApi.ascan = ascan;
        execution = new ZapEngineScanExecution(clientApi);
    }

    @Test
    void startsSpiderScanBehindGatewayExecutionBoundary() throws Exception {
        when(spider.scan("http://example.com", "7", "true", "", "false"))
                .thenReturn(new ApiResponseElement("scan", "55"));

        String scanId = execution.startSpiderScan(new SpiderScanRequest(
                "http://example.com",
                7,
                3,
                12
        ));

        assertThat(scanId).isEqualTo("55");
        verify(core).accessUrl("http://example.com", "true");
        verify(spider).setOptionThreadCount(3);
        verify(spider).setOptionMaxDuration(12);
        verify(spider).scan("http://example.com", "7", "true", "", "false");
    }

    @Test
    void startsActiveScanBehindGatewayExecutionBoundary() throws Exception {
        when(ascan.scan("http://example.com", "true", "false", "Default Policy", null, null))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String scanId = execution.startActiveScan(new ActiveScanRequest(
                "http://example.com",
                "true",
                "Default Policy",
                30,
                5,
                10
        ));

        assertThat(scanId).isEqualTo("101");
        verify(ascan).enableAllScanners(isNull());
        verify(ascan).setOptionMaxScanDurationInMins(30);
        verify(ascan).setOptionHostPerScan(5);
        verify(ascan).setOptionThreadPerHost(10);
        verify(ascan).scan("http://example.com", "true", "false", "Default Policy", null, null);
    }

    @Test
    void rejectsNonNumericSpiderProgressValues() throws Exception {
        when(spider.status("spider-1")).thenReturn(new ApiResponseElement("status", "running"));

        assertThatThrownBy(() -> execution.readSpiderProgressPercent("spider-1"))
                .isInstanceOf(ZapApiException.class)
                .hasMessageContaining("spider.status()")
                .hasMessageNotContaining("running")
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void rejectsNonNumericActiveScanProgressValues() throws Exception {
        when(ascan.status("active-1")).thenReturn(new ApiResponseElement("status", "running"));

        assertThatThrownBy(() -> execution.readActiveScanProgressPercent("active-1"))
                .isInstanceOf(ZapApiException.class)
                .hasMessageContaining("ascan.status()")
                .hasMessageNotContaining("running")
                .hasCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void parsesActiveScanPolicyRulesAtTheAdapterBoundary() throws Exception {
        when(ascan.scanners("Default Policy", null)).thenReturn(new ApiResponseList("scanners", List.of(
                scannerRule("40018", "SQL Injection", "4", true, "DEFAULT", "DEFAULT"),
                scannerRule("6", "Path Traversal", "0", false, "LOW", "HIGH")
        )));

        List<ScannerRuleSnapshot> rules = execution.loadActiveScanPolicyRules("Default Policy");

        assertThat(rules).extracting(ScannerRuleSnapshot::id).containsExactly("6", "40018");
        assertThat(rules.getFirst().hasOverride()).isTrue();
        assertThat(rules.getFirst().dependencies()).isEmpty();
    }

    @Test
    void updatesActiveScanPolicyRulesAtTheAdapterBoundary() throws Exception {
        execution.updateActiveScanRuleState(new ActiveScanRuleMutation(
                "Default Policy",
                List.of("40012", "40018"),
                false,
                "LOW",
                "HIGH"
        ));

        verify(ascan).disableScanners("40012,40018", "Default Policy");
        verify(ascan).setScannerAttackStrength("40012", "LOW", "Default Policy");
        verify(ascan).setScannerAttackStrength("40018", "LOW", "Default Policy");
        verify(ascan).setScannerAlertThreshold("40012", "HIGH", "Default Policy");
        verify(ascan).setScannerAlertThreshold("40018", "HIGH", "Default Policy");
    }

    private ApiResponseSet scannerRule(String id,
                                       String name,
                                       String policyId,
                                       boolean enabled,
                                       String attackStrength,
                                       String alertThreshold) {
        Map<String, ApiResponse> values = new LinkedHashMap<>();
        values.put("id", new ApiResponseElement("id", id));
        values.put("name", new ApiResponseElement("name", name));
        values.put("policyId", new ApiResponseElement("policyId", policyId));
        values.put("enabled", new ApiResponseElement("enabled", Boolean.toString(enabled)));
        values.put("attackStrength", new ApiResponseElement("attackStrength", attackStrength));
        values.put("alertThreshold", new ApiResponseElement("alertThreshold", alertThreshold));
        values.put("quality", new ApiResponseElement("quality", "release"));
        values.put("status", new ApiResponseElement("status", "release"));
        values.put("dependencies", new ApiResponseList("dependencies", List.of()));
        return new ApiResponseSet("scanner", values);
    }
}
