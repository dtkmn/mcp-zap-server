package mcp.server.zap.core.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Addon;
import mcp.server.zap.core.gateway.ZapEngineAddonAccess.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.AjaxSpider;
import org.zaproxy.clientapi.gen.Ascan;
import org.zaproxy.clientapi.gen.Automation;
import org.zaproxy.clientapi.gen.Autoupdate;
import org.zaproxy.clientapi.gen.ClientSpider;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Pscan;
import org.zaproxy.clientapi.gen.Spider;

class ZapEngineAddonAccessTest {
    @TempDir Path directory;
    ClientApi client;
    ZapEngineAddonAccess access;
    EngineMaintenanceGate gate;
    Inventory inventory;

    @BeforeEach void setup() throws Exception {
        client = mock(ClientApi.class);
        client.core = mock(Core.class);
        client.autoupdate = mock(Autoupdate.class);
        client.pscan = mock(Pscan.class);
        client.spider = mock(Spider.class);
        client.ascan = mock(Ascan.class);
        client.ajaxSpider = mock(AjaxSpider.class);
        client.automation = mock(Automation.class);
        client.clientSpider = mock(ClientSpider.class);
        access = new ZapEngineAddonAccess(client);
        AddonMaintenanceProperties properties = new AddonMaintenanceProperties();
        properties.setStateDirectory(directory.toString());
        properties.setUpdatesEnabled(true);
        gate = new EngineMaintenanceGate(properties);
        inventory = new Inventory("2.17.0", List.of(new Addon("spiderAjax", "23.30.0", "release", "INSTALLED")), List.of());
        when(client.spider.scans()).thenReturn(list());
        when(client.ascan.scans()).thenReturn(list());
        when(client.ajaxSpider.status()).thenReturn(element("stopped"));
        when(client.pscan.recordsToScan()).thenReturn(element("0"));
        when(client.pscan.currentTasks()).thenReturn(list());
    }

    @Test void mapsOnlyBoundedSafeMetadataWithoutUrlsDescriptionsOrPaths() throws Exception {
        when(client.core.version()).thenReturn(element("2.17.0"));
        ApiResponseSet addon = set(Map.of("id", "pscanrules", "version", "77.0.0", "status", "release",
                "installationStatus", "INSTALLED", "url", "https://private.invalid/user?token=secret", "file", "/private/path"));
        when(client.autoupdate.installedAddons()).thenReturn(list(addon));
        when(client.autoupdate.localAddons()).thenReturn(list(addon));
        var result = access.inventory();
        assertThat(result.installed()).containsExactly(new Addon("pscanrules", "77.0.0", "release", "INSTALLED"));
        assertThat(result.toString()).doesNotContain("secret", "/private/path");
    }

    @Test void duplicateOrMalformedAddonInventoryIsRejected() throws Exception {
        when(client.core.version()).thenReturn(element("2.17.0"));
        ApiResponseSet addon = set(Map.of("id", "pscanrules", "version", "77.0.0", "status", "release", "installationStatus", "INSTALLED"));
        when(client.autoupdate.installedAddons()).thenReturn(list(addon, addon));
        assertThatThrownBy(access::inventory).isInstanceOf(IllegalStateException.class);
        when(client.autoupdate.installedAddons()).thenReturn(list(element("unexpected")));
        assertThatThrownBy(access::inventory).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "unknown", "yes", "2"})
    void malformedPassiveRuleEnabledStateIsNotReadiness(String enabled) throws Exception {
        when(client.pscan.scanners()).thenReturn(list(set(Map.of("id", "20020", "enabled", enabled, "alertThreshold", "MEDIUM"))));
        assertThatThrownBy(access::passiveRules).isInstanceOf(IllegalStateException.class);
    }

    @Test void requiresBothPassiveQueueAndActiveTasksToBeEmpty() throws Exception {
        access.requireQuiescent(gate, inventory);
        when(client.pscan.currentTasks()).thenReturn(list(set(Map.of("name", "PHP rule"))));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.pscan.currentTasks()).thenReturn(element("unknown"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.pscan.currentTasks()).thenReturn(list());
        when(client.pscan.recordsToScan()).thenReturn(element("1"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
    }

    @Test void anyHttpActiveOrBrowserWorkAndUnknownResponsesRefuseMaintenance() throws Exception {
        when(client.spider.scans()).thenReturn(list(set(Map.of("progress", "99"))));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.spider.scans()).thenReturn(list());
        when(client.ascan.scans()).thenReturn(list(set(Map.of("progress", "0"))));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.ascan.scans()).thenReturn(list());
        when(client.ajaxSpider.status()).thenReturn(element("running"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.ajaxSpider.status()).thenReturn(element("unknown"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "false", "unknown", "1", "2026-99-99T00:00:00Z"})
    void automationCompletionMustBeMeasuredValidTimestamp(String finished) throws Exception {
        gate.apiCall("automation", "action", "runPlan", () -> new ApiResponseElement("planId", "5"));
        when(client.automation.planProgress("5")).thenReturn(set(Map.of("finished", finished)));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        assertThat(gate.trackedActivities()).containsKey("automation:5");
        verify(client.pscan, never()).currentTasks();
    }

    @Test void checksPlanAndBrowserCompletionBeforeFinalPassiveDrain() throws Exception {
        gate.apiCall("automation", "action", "runPlan", () -> new ApiResponseElement("planId", "5"));
        gate.apiCall("clientSpider", "action", "scan", () -> new ApiResponseElement("scan", "7"));
        when(client.automation.planProgress("5")).thenReturn(set(Map.of("finished", "2026-10-23T01:00:00Z")));
        when(client.clientSpider.status("7")).thenReturn(element("99"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.clientSpider.status("7")).thenReturn(element("100"));
        when(client.pscan.recordsToScan()).thenReturn(element("2"));
        assertThatThrownBy(() -> access.requireQuiescent(gate, inventory)).isInstanceOf(IllegalStateException.class);
        when(client.pscan.recordsToScan()).thenReturn(element("0"));
        access.requireQuiescent(gate, inventory);
        assertThat(gate.trackedActivities()).isEmpty();
    }

    @Test void installOnlyCallsOfficialMarketplaceIdAndRequiresOkResponse() throws Exception {
        when(client.autoupdate.installAddon("pscanrules")).thenReturn(element("FAIL"));
        assertThatThrownBy(() -> access.install("pscanrules")).isInstanceOf(ClientApiException.class);
        verify(client.autoupdate, never()).installLocalAddon(any());
        verify(client.autoupdate, never()).uninstallAddon(any());
        verify(client.core, never()).shutdown();
    }

    private static ApiResponseElement element(String value) { return new ApiResponseElement("value", value); }
    private static ApiResponseSet set(Map<String, String> values) {
        Map<String, ApiResponse> fields = new LinkedHashMap<>();
        values.forEach((key, value) -> fields.put(key, new ApiResponseElement(key, value)));
        return new ApiResponseSet("entry", fields);
    }
    private static ApiResponseList list(ApiResponse... values) { return new ApiResponseList("entries", values); }
}
