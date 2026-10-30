package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.ClientSpiderScanRequest;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientSpiderServiceTest {

    private EngineScanExecution engine;
    private UrlValidationService urlValidation;
    private NativeScanLaunch launch;
    private ClientSpiderService service;
    private ScanLimitProperties scanLimits;

    @BeforeEach
    void setup() {
        engine = mock(EngineScanExecution.class);
        urlValidation = mock(UrlValidationService.class);
        launch = mock(NativeScanLaunch.class);
        scanLimits = new ScanLimitProperties();
        service = new ClientSpiderService(engine, urlValidation, scanLimits);
    }

    @Test
    void trackedJobStartUsesSharedDepthDefaultAndPassesLaunchOwnership() {
        when(engine.startClientSpiderScan(new ClientSpiderScanRequest("http://example.com", 10, 15), launch)).thenReturn("7");

        assertThat(service.startClientSpiderJob("http://example.com", null, null, null, launch)).isEqualTo("7");

        verify(urlValidation).validateUrl("http://example.com");
        verify(engine).startClientSpiderScan(new ClientSpiderScanRequest("http://example.com", 10, 15), launch);
    }

    @Test
    void queuedStartUsesRequestedDepthWithoutRegisteringADirectOperation() {
        when(engine.startClientSpiderScan(new ClientSpiderScanRequest("http://example.com", 0, 15), launch)).thenReturn("8");

        assertThat(service.startClientSpiderJob("http://example.com", 0, null, null, launch)).isEqualTo("8");

    }

    @Test
    void authenticatedTrackedJobStartUsesNormalizedNamesAndPassesLaunchOwnership() {
        ClientSpiderScanRequest request = new ClientSpiderScanRequest(
                "http://example.com", 10, 15, "member-context", "member-user");
        when(engine.startClientSpiderScan(request, launch)).thenReturn("9");

        assertThat(service.startClientSpiderJob("http://example.com", null, " member-context ", " member-user ", launch))
                .isEqualTo("9");

        verify(engine).startClientSpiderScan(request, launch);
    }

    @Test
    void authenticatedQueuedStartPassesNamesAndSharedLimitsWithoutRegisteringADirectOperation() {
        scanLimits.setMaxSpiderScanDurationInMins(2);
        ClientSpiderScanRequest request = new ClientSpiderScanRequest(
                "http://example.com", 3, 2, "member-context", "member-user");
        when(engine.startClientSpiderScan(request, launch)).thenReturn("10");

        assertThat(service.startClientSpiderJob("http://example.com", 3, "member-context", "member-user", launch))
                .isEqualTo("10");

        verify(engine).startClientSpiderScan(request, launch);
    }

    @Test
    void blankAuthenticationNamesPreserveAnonymousCrawls() {
        ClientSpiderScanRequest request = new ClientSpiderScanRequest("http://example.com", 10, 15);
        when(engine.startClientSpiderScan(request, launch)).thenReturn("11");

        assertThat(service.startClientSpiderJob("http://example.com", null, " ", " ", launch))
                .isEqualTo("11");

        verify(engine).startClientSpiderScan(request, launch);
    }

    @Test
    void rejectsIncompleteAuthenticationNamesBeforeLaunchingAScan() {
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, "context", null, launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, null, "user", launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, "context", " ", launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, " ", "user", launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contextName and userName");

        verifyNoInteractions(engine, launch);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void trackedAndUntrackedJobStartsUseTheExistingSpiderDurationSetting(int minutes) {
        scanLimits.setMaxSpiderScanDurationInMins(minutes);
        ClientSpiderScanRequest request = new ClientSpiderScanRequest("http://example.com", 10, minutes);
        when(engine.startClientSpiderScan(request, launch)).thenReturn("7");
        when(engine.startClientSpiderScan(request)).thenReturn("8");

        assertThat(service.startClientSpiderJob("http://example.com", null, null, null, launch)).isEqualTo("7");
        assertThat(service.startClientSpiderJob("http://example.com", null, null, null, NativeScanLaunch.UNTRACKED)).isEqualTo("8");

        verify(engine).startClientSpiderScan(request, launch);
        verify(engine).startClientSpiderScan(request);
    }

    @Test
    void rejectsNegativeDurationBeforeStartingACrawl() {
        scanLimits.setMaxSpiderScanDurationInMins(-1);

        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", null, null, null, launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxSpiderScanDurationInMins");
        verifyNoInteractions(engine, launch);
    }

    @Test
    void rejectsInvalidInputBeforeLaunchingTheBrowser() {
        assertThatThrownBy(() -> service.startClientSpiderJob("http://example.com", -1, null, null, launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDepth");
        doThrow(new IllegalArgumentException("URL not allowed")).when(urlValidation).validateUrl("http://blocked");
        assertThatThrownBy(() -> service.startClientSpiderJob("http://blocked", 1, null, null, launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("URL not allowed");

        verifyNoInteractions(engine);
    }

    @Test
    void statusTracksOnlyThisScanAndDoesNotClaimStoppedMeansSuccess() {
        when(engine.readClientSpiderProgressPercent("7")).thenReturn(30, 100);

        assertThat(service.getClientSpiderStatus(" 7 ")).contains("Progress: 30%");
        assertThat(service.getClientSpiderStatus("7"))
                .contains("Scan is no longer running", "stopped scans", "zap_passive_scan_wait");
    }

    @Test
    void stopTargetsTheNativeIdAndRetainsOwnershipWhenTheRequestFails() {
        ZapApiException failure = new ZapApiException("Unavailable", new IllegalStateException());
        doThrow(failure).when(engine).stopClientSpiderScan("7");

        assertThatThrownBy(() -> service.stopClientSpider(" 7 ")).isSameAs(failure);

        assertThat(service.stopClientSpider(" 8 ")).contains("stop requested", "Scan ID: 8");
        verify(engine).stopClientSpiderScan("8");
    }

    @Test
    void rejectsBlankScanIdsBeforeStatusOrStopCalls() {
        assertThatThrownBy(() -> service.getClientSpiderStatus(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.stopClientSpider(null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(engine);
    }
}
