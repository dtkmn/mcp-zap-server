package mcp.server.zap.service;

import mcp.server.zap.configuration.ScanLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Spider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
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
        
        // Setup default mock behavior
        when(scanLimitProperties.getConnectionTimeoutInSecs()).thenReturn(60);
        when(scanLimitProperties.getSpiderThreadCount()).thenReturn(5);
        when(scanLimitProperties.getMaxSpiderScanDurationInMins()).thenReturn(15);
        when(scanLimitProperties.getSpiderMaxDepth()).thenReturn(10);
        
        service = new SpiderScanService(clientApi, urlValidationService, scanLimitProperties);
    }

    @Test
    void startSpiderReturnsScanId() throws Exception {
        when(spider.scan(any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("scan", "55"));
        String result = service.startSpider("http://example.com");
        assertEquals("Spider scan started with ID: 55", result);
        verify(urlValidationService).validateUrl("http://example.com");
        verify(spider).scan("http://example.com", "10", "true", "", "false");
    }

    @Test
    void getSpiderStatusReturnsValue() throws Exception {
        when(spider.status("1")).thenReturn(new ApiResponseElement("status", "80"));
        String result = service.getSpiderStatus("1");
        assertEquals("80", result);
    }
}