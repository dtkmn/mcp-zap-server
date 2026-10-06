package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @BeforeEach
    void setup() {
        engineScanExecution = mock(EngineScanExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getSpiderThreadCount()).thenReturn(5);
        when(scanLimitProperties.getMaxSpiderScanDurationInMins()).thenReturn(15);
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(10);
        when(scanLimitProperties.getSpiderMaxChildren()).thenReturn(3);

        service = new SpiderScanService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void startSpiderScanReturnsDirectMessage() {
        when(engineScanExecution.startSpiderScan(new SpiderScanRequest("http://example.com", 10, 5, 15, 3)))
                .thenReturn("55");

        String result = service.startSpiderScan("http://example.com");

        assertTrue(result.contains("Direct spider scan started."));
        assertTrue(result.contains("Scan ID: 55"));
        assertTrue(result.contains("Use 'zap_spider_status'"));
        verify(urlValidationService).validateUrl("http://example.com");
    }

    @Test
    void startSpiderScanAsUserReturnsDirectMessage() {
        when(engineScanExecution.startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "3", "true", "false", 5, 15, 10)))
                .thenReturn("77");

        String result = service.startSpiderScanAsUser("2", "9", "http://example.com", null, null, null);

        assertTrue(result.contains("Direct authenticated spider scan started."));
        assertTrue(result.contains("Scan ID: 77"));
        assertTrue(result.contains("Context ID: 2"));
        assertTrue(result.contains("User ID: 9"));
        verify(urlValidationService).validateUrl("http://example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", " 7 "})
    void authenticatedChildOverrideDoesNotOverrideConfiguredDepth(String maxChildren) {
        service.startSpiderScanAsUserJob(" 2 ", " 9 ", "http://example.com", maxChildren, "false", "true");

        verify(engineScanExecution).startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", maxChildren.trim(), "false", "true", 5, 15, 10));
    }

    @Test
    void authenticatedDefaultsAllowZeroDepthAndChildren() {
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(0);
        when(scanLimitProperties.getSpiderMaxChildren()).thenReturn(0);

        service.startSpiderScanAsUserJob("2", "9", "http://example.com", null, null, null);

        verify(engineScanExecution).startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "0", "true", "false", 5, 15, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "invalid", "2147483648"})
    void invalidAuthenticatedChildLimitsDoNotLaunchCrawls(String maxChildren) {
        assertThatThrownBy(() -> service.startSpiderScanAsUserJob(
                "2", "9", "http://example.com", maxChildren, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxChildren");
        verifyNoInteractions(engineScanExecution);
    }

    @Test
    void negativeConfiguredDepthDoesNotLaunchEitherHttpCrawl() {
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(-1);

        assertThatThrownBy(() -> service.startSpiderScanJob("http://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.startSpiderScanAsUserJob(
                "2", "9", "http://example.com", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(engineScanExecution);
    }

    @Test
    void getSpiderScanStatusReturnsDirectMessage() {
        when(engineScanExecution.readSpiderProgressPercent("1")).thenReturn(80);

        String result = service.getSpiderScanStatus("1");

        assertTrue(result.contains("Direct spider scan status:"));
        assertTrue(result.contains("Progress: 80%"));
        assertTrue(result.contains("Completed: no"));
    }

    @Test
    void stopSpiderScanReturnsDirectMessage() {
        String result = service.stopSpiderScan("9");

        assertTrue(result.contains("Direct spider scan stopped."));
        assertTrue(result.contains("Scan ID: 9"));
        verify(engineScanExecution).stopSpiderScan("9");
    }

}
