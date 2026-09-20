package mcp.server.zap.core.service;

import java.util.Map;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ClientSpiderScanRequest;
import mcp.server.zap.core.history.ScanHistoryLedgerService;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import mcp.server.zap.core.service.protection.OperationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientSpiderServiceTest {

    private EngineScanExecution engine;
    private UrlValidationService urlValidation;
    private OperationRegistry operations;
    private ScanHistoryLedgerService history;
    private ClientSpiderService service;
    private ScanLimitProperties scanLimits;

    @BeforeEach
    void setup() {
        engine = mock(EngineScanExecution.class);
        urlValidation = mock(UrlValidationService.class);
        operations = mock(OperationRegistry.class);
        history = mock(ScanHistoryLedgerService.class);
        ClientWorkspaceResolver workspaces = mock(ClientWorkspaceResolver.class);
        when(workspaces.resolveCurrentWorkspaceId()).thenReturn("workspace-a");
        scanLimits = new ScanLimitProperties();
        service = new ClientSpiderService(engine, urlValidation, scanLimits);
        service.setOperationRegistry(operations);
        service.setClientWorkspaceResolver(workspaces);
        service.setScanHistoryLedgerService(history);
    }

    @Test
    void directStartUsesSharedDepthDefaultAndRegistersItsOwnOperationNamespace() {
        when(engine.startClientSpiderScan(new ClientSpiderScanRequest("http://example.com", 10, 15))).thenReturn("7");

        assertThat(service.startClientSpider("http://example.com", null)).contains("Scan ID: 7");

        verify(urlValidation).validateUrl("http://example.com");
        verify(operations).registerDirectScan("client-spider:7", "workspace-a");
        verify(history).recordDirectScanStarted("client_spider", "7", "http://example.com", Map.of());
    }

    @Test
    void queuedStartUsesRequestedDepthWithoutRegisteringADirectOperation() {
        when(engine.startClientSpiderScan(new ClientSpiderScanRequest("http://example.com", 0, 15))).thenReturn("8");

        assertThat(service.startClientSpiderJob("http://example.com", 0)).isEqualTo("8");

        verifyNoInteractions(operations, history);
    }

    @Test
    void authenticatedDirectStartUsesNormalizedNamesAndRecordsAuthenticationWithoutCredentials() {
        ClientSpiderScanRequest request = new ClientSpiderScanRequest(
                "http://example.com", 10, 15, "member-context", "member-user");
        when(engine.startClientSpiderScan(request)).thenReturn("9");

        assertThat(service.startClientSpider("http://example.com", null, " member-context ", " member-user "))
                .contains("Scan ID: 9");

        verify(engine).startClientSpiderScan(request);
        verify(operations).registerDirectScan("client-spider:9", "workspace-a");
        verify(history).recordDirectScanStarted("client_spider", "9", "http://example.com",
                Map.of("authenticated", "true"));
    }

    @Test
    void authenticatedQueuedStartPassesNamesAndSharedLimitsWithoutRegisteringADirectOperation() {
        scanLimits.setMaxSpiderScanDurationInMins(2);
        ClientSpiderScanRequest request = new ClientSpiderScanRequest(
                "http://example.com", 3, 2, "member-context", "member-user");
        when(engine.startClientSpiderScan(request)).thenReturn("10");

        assertThat(service.startClientSpiderJob("http://example.com", 3, "member-context", "member-user"))
                .isEqualTo("10");

        verify(engine).startClientSpiderScan(request);
        verifyNoInteractions(operations, history);
    }

    @Test
    void blankAuthenticationNamesPreserveAnonymousCrawls() {
        ClientSpiderScanRequest request = new ClientSpiderScanRequest("http://example.com", 10, 15);
        when(engine.startClientSpiderScan(request)).thenReturn("11");

        assertThat(service.startClientSpider("http://example.com", null, " ", " "))
                .contains("Scan ID: 11");

        verify(engine).startClientSpiderScan(request);
        verify(history).recordDirectScanStarted("client_spider", "11", "http://example.com", Map.of());
    }

    @Test
    void rejectsIncompleteAuthenticationNamesBeforeLaunchingOrRecordingAScan() {
        assertThatThrownBy(() -> service.startClientSpider("http://example.com", null, "context", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, null, "user"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, "context", " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpider("http://example.com", null, " ", "user"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");

        verifyNoInteractions(engine, operations, history);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void directAndQueuedStartsUseTheExistingSpiderDurationSetting(int minutes) {
        scanLimits.setMaxSpiderScanDurationInMins(minutes);
        ClientSpiderScanRequest request = new ClientSpiderScanRequest("http://example.com", 10, minutes);
        when(engine.startClientSpiderScan(request)).thenReturn("7", "8");

        assertThat(service.startClientSpider("http://example.com", null)).contains("Scan ID: 7");
        assertThat(service.startClientSpiderJob("http://example.com", null)).isEqualTo("8");

        verify(engine, times(2)).startClientSpiderScan(request);
    }

    @Test
    void rejectsNegativeDurationBeforeStartingACrawl() {
        scanLimits.setMaxSpiderScanDurationInMins(-1);

        assertThatThrownBy(() -> service.startClientSpider("http://example.com", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSpiderScanDurationInMins");
        verifyNoInteractions(engine, operations, history);
    }

    @Test
    void rejectsInvalidInputBeforeLaunchingTheBrowser() {
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDepth");
        doThrow(new IllegalArgumentException("URL not allowed")).when(urlValidation).validateUrl("http://blocked");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://blocked", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("URL not allowed");

        verifyNoInteractions(engine, operations, history);
    }

    @Test
    void statusTracksOnlyThisScanAndDoesNotClaimStoppedMeansSuccess() {
        when(engine.readClientSpiderProgressPercent("7")).thenReturn(30, 100);

        assertThat(service.getClientSpiderStatus(" 7 ")).contains("Progress: 30%");
        verify(operations).touchDirectScan("client-spider:7");
        assertThat(service.getClientSpiderStatus("7"))
                .contains("Scan is no longer running", "stopped scans", "zap_passive_scan_wait");
        verify(operations).releaseDirectScan("client-spider:7");
    }

    @Test
    void stopTargetsTheNativeIdAndRetainsOwnershipWhenTheRequestFails() {
        ZapApiException failure = new ZapApiException("Unavailable", new IllegalStateException());
        doThrow(failure).when(engine).stopClientSpiderScan("7");

        assertThatThrownBy(() -> service.stopClientSpider(" 7 ")).isSameAs(failure);
        verify(operations, never()).releaseDirectScan("client-spider:7");

        assertThat(service.stopClientSpider(" 8 ")).contains("stop requested", "Scan ID: 8");
        verify(engine).stopClientSpiderScan("8");
        verify(operations).releaseDirectScan("client-spider:8");
    }

    @Test
    void rejectsBlankScanIdsBeforeStatusOrStopCalls() {
        assertThatThrownBy(() -> service.getClientSpiderStatus(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stopClientSpider(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(engine, operations);
    }
}
