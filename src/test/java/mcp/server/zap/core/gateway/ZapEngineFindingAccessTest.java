package mcp.server.zap.core.gateway;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import mcp.server.zap.core.gateway.EngineFindingAccess.AlertSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseFactory;
import org.zaproxy.clientapi.core.ClientApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZapEngineFindingAccessTest {

    private org.zaproxy.clientapi.gen.Alert alertApi;
    private ZapEngineFindingAccess findingAccess;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        alertApi = mock(org.zaproxy.clientapi.gen.Alert.class);
        clientApi.alert = alertApi;
        findingAccess = new ZapEngineFindingAccess(clientApi);
    }

    @Test
    void preservesNodeNameAndEveryTagFromZapXmlResponse() throws Exception {
        // ZAP AlertAPI emits tags as a list of tag sets, each with key/value elements.
        ApiResponse response = parseAlertXml(
                "<nodeName>Missing CSP (https://target)</nodeName>"
                        + "<tags type=\"list\">"
                        + "<tag type=\"set\"><key>SYSTEMIC</key><value>https://example.test/systemic</value></tag>"
                        + "<tag type=\"set\"><key>custom &amp; detail</key><value>https://example.test/?a=1&amp;b=2</value></tag>"
                        + "<tag type=\"set\"><key>empty-value</key><value/></tag>"
                        + "</tags>");
        when(alertApi.alerts("https://target", "0", "-1", null, null, null)).thenReturn(response);

        AlertSnapshot alert = findingAccess.loadAlerts("https://target").getFirst();

        assertThat(alert.name()).isEqualTo("Missing CSP");
        assertThat(alert.nodeName()).isEqualTo("Missing CSP (https://target)");
        assertThat(alert.tags()).isEqualTo(Map.of(
                "SYSTEMIC", "https://example.test/systemic",
                "custom & detail", "https://example.test/?a=1&b=2",
                "empty-value", ""));
    }

    @Test
    void preservesHttpMethodFromZapXmlResponse() throws Exception {
        for (String method : List.of("GET", "POST")) {
            when(alertApi.alerts("https://target", "0", "-1", null, null, null))
                    .thenReturn(parseAlertXml("<method>" + method + "</method>"));

            assertThat(findingAccess.loadAlerts("https://target").getFirst().method()).isEqualTo(method);
        }
    }

    @Test
    void acceptsMissingMetadataAndEmptyTagLists() throws Exception {
        for (String metadata : List.of("", "<tags type=\"list\"/>")) {
            when(alertApi.alerts("https://target", "0", "-1", null, null, null))
                    .thenReturn(parseAlertXml(metadata));

            AlertSnapshot alert = findingAccess.loadAlerts("https://target").getFirst();

            assertThat(alert.nodeName()).isNull();
            assertThat(alert.method()).isNull();
            assertThat(alert.tags()).isEmpty();
            assertThat(alert.pluginId()).isEqualTo("10038");
            assertThat(alert.risk()).isEqualTo("Low");
        }
    }

    @Test
    void keepsLegacyConstructorAndNullTagMapCompatible() {
        AlertSnapshot legacy = new AlertSnapshot("1", "10038", "Missing CSP", "Description",
                "Low", "Medium", "https://target", "", "", "", "", "", "101", "693", "15");

        assertThat(legacy.nodeName()).isNull();
        assertThat(legacy.method()).isNull();
        assertThat(legacy.tags()).isEmpty();
        assertThat(snapshotWithTags(null).tags()).isEmpty();
    }

    @Test
    void takesAnImmutableCopyOfTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("SYSTEMIC", "original");

        AlertSnapshot alert = snapshotWithTags(tags);
        tags.put("SYSTEMIC", "changed");
        tags.put("new-tag", "new-value");

        assertThat(alert.tags()).containsExactlyEntriesOf(Map.of("SYSTEMIC", "original"));
        assertThatThrownBy(() -> alert.tags().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedTagEntriesInsteadOfSilentlyDroppingMetadata() throws Exception {
        when(alertApi.alerts("https://target", "0", "-1", null, null, null))
                .thenReturn(parseAlertXml("<tags type=\"list\"><tag type=\"set\"><key>SYSTEMIC</key></tag></tags>"));

        assertThatThrownBy(() -> findingAccess.loadAlerts("https://target"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected key and value");
    }

    private AlertSnapshot snapshotWithTags(Map<String, String> tags) {
        return new AlertSnapshot("1", "10038", "Missing CSP", "Description", "Low", "Medium",
                "https://target", "", "", "", "", "", "101", "693", "15", "Missing CSP", "GET", tags);
    }

    private ApiResponse parseAlertXml(String metadata) throws Exception {
        String xml = "<alerts type=\"list\"><alert type=\"set\">"
                + "<id>1</id><pluginId>10038</pluginId><name>Missing CSP</name>"
                + "<risk>Low</risk><confidence>Medium</confidence><url>https://target</url>"
                + metadata + "</alert></alerts>";
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        return ApiResponseFactory.getResponse(document.getDocumentElement());
    }
}
