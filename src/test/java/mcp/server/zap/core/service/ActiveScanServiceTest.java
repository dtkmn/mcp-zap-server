package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Ascan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
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

        when(scanLimitProperties.getMaxActiveScanDurationInMins()).thenReturn(30);
        when(scanLimitProperties.getHostPerScan()).thenReturn(5);
        when(scanLimitProperties.getThreadPerHost()).thenReturn(10);

        service = new ActiveScanService(clientApi, urlValidationService, scanLimitProperties);
    }

    @Test
    void startActiveScanJobReturnsScanId() throws Exception {
        when(ascan.scan(any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String scanId = service.startActiveScanJob("http://example.com", "true", "Default Policy");

        assertEquals("101", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(ascan).scan("http://example.com", "true", "false", "Default Policy", null, null);
    }

    @Test
    void startActiveScanAsUserJobReturnsScanId() throws Exception {
        when(ascan.scanAsUser(anyString(), anyString(), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "202"));

        String scanId = service.startActiveScanAsUserJob("1", "3", "http://example.com", null, "Default Policy");

        assertEquals("202", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(ascan).scanAsUser("http://example.com", "1", "3", "true", "Default Policy", null, null);
    }

    @Test
    void getActiveScanProgressPercentReturnsValue() throws Exception {
        when(ascan.status("1")).thenReturn(new ApiResponseElement("status", "42"));

        int progress = service.getActiveScanProgressPercent("1");

        assertEquals(42, progress);
    }

    @Test
    void stopActiveScanJobCallsApi() throws Exception {
        service.stopActiveScanJob("2");

        verify(ascan).stop("2");
    }

    @Test
    void stopActiveScanJobHandlesException() throws Exception {
        doThrow(new ClientApiException("boom", null)).when(ascan).stop("2");

        assertThrowsExactly(ZapApiException.class, () -> service.stopActiveScanJob("2"));
    }
}
