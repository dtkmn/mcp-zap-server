package mcp.server.zap.core.service;

import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Spider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SpiderScanServiceTest {
    private Spider spider;
    private Core core;
    private SpiderScanService service;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        spider = mock(Spider.class);
        core = mock(Core.class);
        clientApi.spider = spider;
        clientApi.core = core;

        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getConnectionTimeoutInSecs()).thenReturn(60);
        when(scanLimitProperties.getSpiderThreadCount()).thenReturn(5);
        when(scanLimitProperties.getMaxSpiderScanDurationInMins()).thenReturn(15);
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(10);

        service = new SpiderScanService(clientApi, urlValidationService, scanLimitProperties);
    }

    @Test
    void startSpiderScanJobReturnsScanId() throws Exception {
        when(spider.scan(any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("scan", "55"));

        String scanId = service.startSpiderScanJob("http://example.com");

        assertEquals("55", scanId);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(spider).scan("http://example.com", "10", "true", "", "false");
    }

    @Test
    void getSpiderScanProgressPercentReturnsValue() throws Exception {
        when(spider.status("1")).thenReturn(new ApiResponseElement("status", "80"));

        int progress = service.getSpiderScanProgressPercent("1");

        assertEquals(80, progress);
    }

    @Test
    void startSpiderScanAsUserJobReturnsScanId() throws Exception {
        when(spider.scanAsUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ApiResponseElement("scan", "77"));

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
        verify(spider).scanAsUser("2", "9", "http://example.com", "10", "true", "false");
    }

    @Test
    void stopSpiderScanJobCallsApi() throws Exception {
        service.stopSpiderScanJob("9");

        verify(spider).stop("9");
    }

    @Test
    void stopSpiderScanJobHandlesException() throws Exception {
        doThrow(new ClientApiException("boom", null)).when(spider).stop("9");

        assertThrowsExactly(ZapApiException.class, () -> service.stopSpiderScanJob("9"));
    }
}
