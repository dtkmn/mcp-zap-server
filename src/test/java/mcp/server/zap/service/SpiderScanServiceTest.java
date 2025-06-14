package mcp.server.zap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.gen.Core;
import org.zaproxy.clientapi.gen.Spider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SpiderScanServiceTest {
    private ClientApi clientApi;
    private Spider spider;
    private Core core;
    private SpiderScanService service;

    @BeforeEach
    void setup() {
        clientApi = new ClientApi("localhost", 0);
        spider = mock(Spider.class);
        core = mock(Core.class);
        clientApi.spider = spider;
        clientApi.core = core;
        service = new SpiderScanService(clientApi);
    }

    @Test
    void startSpiderReturnsScanId() throws Exception {
        when(spider.scan(any(), any(), any(), any(), any()))
                .thenReturn(new ApiResponseElement("scan", "55"));
        String result = service.startSpider("http://example.com");
        assertEquals("Spider scan started with ID: 55", result);
        verify(spider).scan("http://example.com", "10", "true", "", "false");
    }

    @Test
    void startSpiderRejectsInvalidUrl() {
        assertThrows(IllegalArgumentException.class, () -> service.startSpider("bad-url"));
    }

    @Test
    void getSpiderStatusReturnsValue() throws Exception {
        when(spider.status("1")).thenReturn(new ApiResponseElement("status", "80"));
        String result = service.getSpiderStatus("1");
        assertEquals("80", result);
    }
}