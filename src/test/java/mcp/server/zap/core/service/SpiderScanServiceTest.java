package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;


class SpiderScanServiceTest {
    private EngineScanExecution engineScanExecution;
    private SpiderScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;
    private NativeScanLaunch launch;

    @BeforeEach
    void setup() {
        engineScanExecution = mock(EngineScanExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);
        launch = mock(NativeScanLaunch.class);

        when(scanLimitProperties.getSpiderThreadCount()).thenReturn(5);
        when(scanLimitProperties.getMaxSpiderScanDurationInMins()).thenReturn(15);
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(10);
        when(scanLimitProperties.getSpiderMaxChildren()).thenReturn(3);

        service = new SpiderScanService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void startSpiderScanJobPassesSharedLimitsAndLaunchOwnership() {
        when(engineScanExecution.startSpiderScan(new SpiderScanRequest("http://example.com", 10, 5, 15, 3), launch))
                .thenReturn("55");

        String result = service.startSpiderScanJob("http://example.com", launch);

        assertEquals("55", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startSpiderScan(new SpiderScanRequest("http://example.com", 10, 5, 15, 3), launch);
    }

    @Test
    void startSpiderScanAsUserJobPassesAuthenticationDefaultsAndLaunchOwnership() {
        when(engineScanExecution.startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "3", "true", "false", 5, 15, 10), launch))
                .thenReturn("77");

        String result = service.startSpiderScanAsUserJob("2", "9", "http://example.com", null, null, null, launch);

        assertEquals("77", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "3", "true", "false", 5, 15, 10), launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", " 7 "})
    void authenticatedChildOverrideDoesNotOverrideConfiguredDepth(String maxChildren) {
        service.startSpiderScanAsUserJob(" 2 ", " 9 ", "http://example.com", maxChildren, "false", "true", launch);

        verify(engineScanExecution).startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", maxChildren.trim(), "false", "true", 5, 15, 10), launch);
    }

    @Test
    void authenticatedDefaultsAllowZeroDepthAndChildren() {
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(0);
        when(scanLimitProperties.getSpiderMaxChildren()).thenReturn(0);

        service.startSpiderScanAsUserJob("2", "9", "http://example.com", null, null, null, launch);

        verify(engineScanExecution).startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "0", "true", "false", 5, 15, 0), launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid", "2147483648"})
    void invalidAuthenticatedChildLimitsDoNotLaunchCrawls(String maxChildren) {
        assertThatThrownBy(() -> service.startSpiderScanAsUserJob(
                "2", "9", "http://example.com", maxChildren, null, null, launch))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxChildren");
        verifyNoInteractions(engineScanExecution);
    }

    @Test
    void negativeConfiguredDepthDoesNotLaunchEitherHttpCrawl() {
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(-1);

        assertThatThrownBy(() -> service.startSpiderScanJob("http://example.com", launch))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startSpiderScanAsUserJob(
                "2", "9", "http://example.com", null, null, null, launch))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engineScanExecution);
    }

    @Test
    void getSpiderScanStatusReturnsDirectMessage() {
        when(engineScanExecution.readSpiderProgressPercent("1")).thenReturn(80, 100);

        String result = service.getSpiderScanStatus("1");

        assertTrue(result.contains("Direct spider scan status:"));
        assertTrue(result.contains("Progress: 80%"));
        assertTrue(result.contains("Completed: no"));
        assertThat(result).contains("Scan ID: 1").doesNotContain("zap_spider_stop", "zap_queue_spider_scan");
        assertThat(service.getSpiderScanStatus("1"))
                .contains("Scan ID: 1", "Progress: 100%", "Completed: yes", "zap_passive_scan_wait")
                .doesNotContain("zap_spider_stop", "zap_queue_spider_scan");
    }

    @Test
    void stopSpiderScanReturnsDirectMessage() {
        String result = service.stopSpiderScan("9");

        assertTrue(result.contains("Direct spider scan stopped."));
        assertTrue(result.contains("Scan ID: 9"));
        assertThat(result).doesNotContain("zap_spider_stop", "zap_queue_spider_scan");
        verify(engineScanExecution).stopSpiderScan("9");
    }

}
