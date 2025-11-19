package mcp.server.zap.service;

import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Ascan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ActiveScanServiceTest {
    private Ascan ascan;
    private ActiveScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        ascan = mock(Ascan.class);
        clientApi.ascan = ascan;
        
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);
        
        // Setup default mock behavior
        when(scanLimitProperties.getMaxActiveScanDurationInMins()).thenReturn(30);
        when(scanLimitProperties.getHostPerScan()).thenReturn(5);
        when(scanLimitProperties.getThreadPerHost()).thenReturn(10);
        
        service = new ActiveScanService(clientApi, urlValidationService, scanLimitProperties);
    }

    @Test
    void activeScanReturnsScanId() throws Exception {
        when(ascan.scan(any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String result = service.activeScan("http://example.com", "true", "Default Policy");

        assertEquals("Active scan started with ID: 101", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(ascan).scan("http://example.com", "true", "false", "Default Policy", null, null);
    }

    @Test
    void getActiveScanStatusReturnsMessage() throws Exception {
        when(ascan.status("1")).thenReturn(new ApiResponseElement("status", "42"));
        String result = service.getActiveScanStatus("1");
        assertEquals("Active Scan [1] is 42% complete", result);
    }

    @Test
    void stopActiveScanCallsApi() throws Exception {
        String msg = service.stopActiveScan("2");
        assertEquals("\uD83D\uDED1 Stopped active scan with ID: 2", msg);
        verify(ascan).stop("2");
    }

    @Test
    void stopAllScansSuccess() throws Exception {
        String msg = service.stopAllScans();
        assertEquals("\uD83D\uDED1 All active scans have been stopped.", msg);
        verify(ascan).stopAllScans();
    }

    @Test
    void stopAllScansHandlesException() throws Exception {
        doThrow(new ClientApiException("boom", null)).when(ascan).stopAllScans();
        assertThrowsExactly(ZapApiException.class, () -> service.stopAllScans());
    }
}