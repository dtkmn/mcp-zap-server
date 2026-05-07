package mcp.server.zap.core.service;

import java.util.List;
import mcp.server.zap.core.gateway.EngineInventoryAccess;
import mcp.server.zap.core.gateway.EngineInventoryAccess.InventoryAlertSummary;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreServiceTest {

    private EngineInventoryAccess inventoryAccess;
    private ScanHistoryLedgerService scanHistoryLedgerService;
    private CoreService service;

    @BeforeEach
    void setup() {
        inventoryAccess = mock(EngineInventoryAccess.class);
        scanHistoryLedgerService = mock(ScanHistoryLedgerService.class);
        service = new CoreService(inventoryAccess);
        service.setScanHistoryLedgerService(scanHistoryLedgerService);
    }

    @Test
    void getAlertsFormatsInventoryAlertSummaries() {
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("http://target")).thenReturn(true);
        when(inventoryAccess.loadAlertSummaries("http://target")).thenReturn(List.of(
                new InventoryAlertSummary("Missing Header", "Low", "http://target/")
        ));

        assertThat(service.getAlerts("http://target"))
                .containsExactly("Missing Header (risk: Low) at http://target/");
    }

    @Test
    void inventoryReadsAreScopedToVisibleScanEvidence() {
        when(scanHistoryLedgerService.visibleScanTargetHosts()).thenReturn(List.of("target"));
        when(scanHistoryLedgerService.visibleScanTargetBaseUrls()).thenReturn(List.of("http://target"));
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("http://target")).thenReturn(true);
        when(inventoryAccess.listUrls("http://target")).thenReturn(List.of(
                "http://target/a",
                "http://other/a",
                "http://target.evil/a",
                "not-a-url"
        ));

        assertThat(service.getHosts()).containsExactly("target");
        assertThat(service.getSites()).containsExactly("http://target");
        assertThat(service.getUrls("http://target")).containsExactly("http://target/a");
    }

    @Test
    void inventoryUrlReadsUseCanonicalPathBoundaries() {
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("https://target/app")).thenReturn(true);
        when(inventoryAccess.listUrls("https://target/app")).thenReturn(List.of(
                "https://target/app",
                "https://target:443/app/deeper",
                "https://target/app2",
                "https://target.evil/app",
                "https://target/%2e%2e/app",
                "not-a-url"
        ));

        assertThat(service.getUrls("https://target/app"))
                .containsExactly("https://target/app", "https://target:443/app/deeper");
    }

    @Test
    void inventoryAlertReadsPostFilterReturnedUrls() {
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("https://target/app")).thenReturn(true);
        when(inventoryAccess.loadAlertSummaries("https://target/app")).thenReturn(List.of(
                new InventoryAlertSummary("Allowed", "Low", "https://target/app/page"),
                new InventoryAlertSummary("Path Prefix Bypass", "High", "https://target/app2"),
                new InventoryAlertSummary("Host Prefix Bypass", "High", "https://target.evil/app"),
                new InventoryAlertSummary("Malformed", "High", "not-a-url")
        ));

        assertThat(service.getAlerts("https://target/app"))
                .containsExactly("Allowed (risk: Low) at https://target/app/page");
    }

    @Test
    void inventoryUrlReadsRejectTargetsWithoutVisibleScanEvidence() {
        when(scanHistoryLedgerService.hasVisibleScanEvidenceForTarget("http://other")).thenReturn(false);

        assertThatThrownBy(() -> service.getUrls("http://other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No visible scan evidence");
    }
}
