package mcp.server.zap.core.service;

import java.util.List;
import mcp.server.zap.core.gateway.EngineInventoryAccess;
import mcp.server.zap.core.gateway.EngineInventoryAccess.InventoryAlertSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreServiceTest {

    private EngineInventoryAccess inventoryAccess;
    private CoreService service;

    @BeforeEach
    void setup() {
        inventoryAccess = mock(EngineInventoryAccess.class);
        service = new CoreService(inventoryAccess);
    }

    @Test
    void getAlertsFormatsInventoryAlertSummaries() {
        when(inventoryAccess.loadAlertSummaries("http://target")).thenReturn(List.of(
                new InventoryAlertSummary("Missing Header", "Low", "http://target/")
        ));

        assertThat(service.getAlerts("http://target"))
                .containsExactly("Missing Header (risk: Low) at http://target/");
    }

    @Test
    void delegatesInventoryListsToGatewayAccess() {
        when(inventoryAccess.listHosts()).thenReturn(List.of("target"));
        when(inventoryAccess.listSites()).thenReturn(List.of("http://target/"));
        when(inventoryAccess.listUrls("http://target")).thenReturn(List.of("http://target/a"));

        assertThat(service.getHosts()).containsExactly("target");
        assertThat(service.getSites()).containsExactly("http://target/");
        assertThat(service.getUrls("http://target")).containsExactly("http://target/a");
    }
}
