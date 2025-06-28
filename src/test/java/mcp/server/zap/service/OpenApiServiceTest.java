package mcp.server.zap.service;

import mcp.server.zap.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Openapi;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class OpenApiServiceTest {
    private ClientApi clientApi;
    private Openapi openapi;
    private OpenApiService service;

    @BeforeEach
    void setup() {
        clientApi = new ClientApi("localhost", 0);
        openapi = mock(Openapi.class);
        clientApi.openapi = openapi;
        service = new OpenApiService(clientApi);
    }

    @Test
    void importOpenApiSpecReturnsAsyncMessage() throws Exception {
        ApiResponseList resp = new ApiResponseList("imports", new ApiResponseElement[]{ new ApiResponseElement("id", "9") });
        when(openapi.importUrl("http://example.com/api.yaml", "host"))
                .thenReturn(resp);

        String result = service.importOpenApiSpec("http://example.com/api.yaml", "host");
        assertTrue(result.contains("jobs: 9"));
    }

    @Test
    void importOpenApiSpecInvalidUrl() throws Exception {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.importOpenApiSpec("bad-url",
            "host"));
    }

    @Test
    void importOpenApiSpecFileHandlesException() throws Exception {
        when(openapi.importFile("/tmp/api.yaml", "host")).thenThrow(new ClientApiException("oops"));
        assertThrowsExactly(ZapApiException.class, () -> service.importOpenApiSpecFile("/tmp/api.yaml",
            "host"));
    }
}