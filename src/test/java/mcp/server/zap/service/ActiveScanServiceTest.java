package mcp.server.zap.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.gen.Ascan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ActiveScanServiceTest {
    private ClientApi clientApi;
    private Ascan ascan;
    private ActiveScanService service;

    @BeforeEach
    void setup() {
        clientApi = new ClientApi("localhost", 0);
        ascan = mock(Ascan.class);
        clientApi.ascan = ascan;
        service = new ActiveScanService(clientApi);
    }

    @Test
    void activeScanReturnsScanId() throws Exception {
        when(ascan.scan(any(), any(), any(), any(), isNull(), isNull()))
                .thenReturn(new ApiResponseElement("scan", "101"));

        String result = service.activeScan("http://example.com", "true", "Default Policy");

        assertEquals("Active scan started with ID: 101", result);
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
        doThrow(new RuntimeException("boom")).when(ascan).stopAllScans();
        String msg = service.stopAllScans();
        assertTrue(msg.contains("boom"));
    }
}