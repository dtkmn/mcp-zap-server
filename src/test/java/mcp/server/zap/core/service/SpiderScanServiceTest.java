package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.EngineScanExecution.AuthenticatedSpiderScanRequest;
import mcp.server.zap.core.gateway.EngineScanExecution.SpiderScanRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SpiderScanServiceTest {
    private EngineScanExecution engineScanExecution;
    private SpiderScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeEach
    void setup() {
        engineScanExecution = mock(EngineScanExecution.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getConnectionTimeoutInSecs()).thenReturn(60);
        when(scanLimitProperties.getSpiderThreadCount()).thenReturn(5);
        when(scanLimitProperties.getMaxSpiderScanDurationInMins()).thenReturn(15);
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(10);

        service = new SpiderScanService(engineScanExecution, urlValidationService, scanLimitProperties);
    }

    @Test
    void startSpiderScanJobReturnsScanId() {
        SpiderScanRequest request = new SpiderScanRequest("http://example.com", 10, 5, 15);
        when(engineScanExecution.startSpiderScan(request)).thenReturn("55");

        String scanId = service.startSpiderScanJob("http://example.com");

        assertEquals("55", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startSpiderScan(request);
    }

    @Test
    void getSpiderScanProgressPercentReturnsValue() {
        when(engineScanExecution.readSpiderProgressPercent("1")).thenReturn(80);

        int progress = service.getSpiderScanProgressPercent("1");

        assertEquals(80, progress);
    }

    @Test
    void startSpiderScanReturnsDirectMessage() {
        when(engineScanExecution.startSpiderScan(new SpiderScanRequest("http://example.com", 10, 5, 15)))
                .thenReturn("55");

        String result = service.startSpiderScan("http://example.com");

        assertTrue(result.contains("Direct spider scan started."));
        assertTrue(result.contains("Scan ID: 55"));
        assertTrue(result.contains("Use 'zap_spider_status'"));
    }

    @Test
    void startSpiderScanAsUserJobReturnsScanId() {
        AuthenticatedSpiderScanRequest request = new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "10", "true", "false", 5, 15);
        when(engineScanExecution.startSpiderScanAsUser(request)).thenReturn("77");

        String scanId = service.startSpiderScanAsUserJob(
                "2",
                "9",
                "http://example.com",
                null,
                null,
                null
        );

        assertEquals("77", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(engineScanExecution).startSpiderScanAsUser(request);
    }

    @Test
    void startSpiderScanAsUserReturnsDirectMessage() {
        when(engineScanExecution.startSpiderScanAsUser(new AuthenticatedSpiderScanRequest(
                "2", "9", "http://example.com", "10", "true", "false", 5, 15)))
                .thenReturn("77");

        String result = service.startSpiderScanAsUser("2", "9", "http://example.com", null, null, null);

        assertTrue(result.contains("Direct authenticated spider scan started."));
        assertTrue(result.contains("Scan ID: 77"));
        assertTrue(result.contains("Context ID: 2"));
        assertTrue(result.contains("User ID: 9"));
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
    void stopSpiderScanJobCallsEngineBoundary() {
        service.stopSpiderScanJob("9");

        verify(engineScanExecution).stopSpiderScan("9");
    }

    @Test
    void stopSpiderScanReturnsDirectMessage() {
        String result = service.stopSpiderScan("9");

        assertTrue(result.contains("Direct spider scan stopped."));
        assertTrue(result.contains("Scan ID: 9"));
        verify(engineScanExecution).stopSpiderScan("9");
    }

    @Test
    void stopSpiderScanJobHandlesException() {
        doThrow(new ZapApiException("boom", new RuntimeException("boom")))
                .when(engineScanExecution).stopSpiderScan("9");

        assertThrowsExactly(ZapApiException.class, () -> service.stopSpiderScanJob("9"));
    }
}
