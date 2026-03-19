package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Graphql;
import org.zaproxy.clientapi.gen.Openapi;
import org.zaproxy.clientapi.gen.Soap;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OpenApiServiceTest {
    private ClientApi clientApi;
    private Openapi openapi;
    private Graphql graphql;
    private Soap soap;
    private OpenApiService service;
    private UrlValidationService urlValidationService;

    @BeforeEach
    void setup() {
        clientApi = new ClientApi("localhost", 0);
        openapi = mock(Openapi.class);
        graphql = mock(Graphql.class);
        soap = mock(Soap.class);
        clientApi.openapi = openapi;
        clientApi.graphql = graphql;
        clientApi.soap = soap;
        urlValidationService = mock(UrlValidationService.class);
        service = new OpenApiService(clientApi, urlValidationService);
    }

    @Test
    void importOpenApiSpecReturnsAsyncMessage() throws Exception {
        ApiResponseList resp = new ApiResponseList("imports", new ApiResponseElement[]{ new ApiResponseElement("id", "9") });
        when(openapi.importUrl("http://example.com/api.yaml", "host"))
                .thenReturn(resp);

        String result = service.importOpenApiSpec("http://example.com/api.yaml", "host");
        assertTrue(result.contains("jobs: 9"));
        verify(urlValidationService).validateUrl("http://example.com/api.yaml");
    }

    @Test
    void importGraphqlSchemaUrlReturnsMessageAndValidatesBothUrls() throws Exception {
        ApiResponseList resp = new ApiResponseList("warnings", new ApiResponseElement[]{
                new ApiResponseElement("warning", "Imported GraphQL schema with warnings")
        });
        when(graphql.importUrl("http://example.com/graphql", "http://example.com/schema.graphql"))
                .thenReturn(resp);

        String result = service.importGraphqlSchemaUrl("http://example.com/graphql", "http://example.com/schema.graphql");

        assertTrue(result.contains("GraphQL import completed with messages"));
        assertTrue(result.contains("Imported GraphQL schema with warnings"));
        verify(urlValidationService).validateUrl("http://example.com/graphql");
        verify(urlValidationService).validateUrl("http://example.com/schema.graphql");
    }

    @Test
    void importGraphqlSchemaFileHandlesException() throws Exception {
        when(graphql.importFile("http://example.com/graphql", "/tmp/schema.graphql")).thenThrow(new ClientApiException("oops"));
        assertThrowsExactly(ZapApiException.class, () -> service.importGraphqlSchemaFile("http://example.com/graphql",
                "/tmp/schema.graphql"));
    }

    @Test
    void importSoapWsdlUrlReturnsSyncMessage() throws Exception {
        when(soap.importUrl("http://example.com/service.wsdl"))
                .thenReturn(new ApiResponseElement("Result", "OK"));

        String result = service.importSoapWsdlUrl("http://example.com/service.wsdl");

        assertTrue(result.contains("SOAP/WSDL import completed with messages"));
        assertTrue(result.contains("OK"));
        verify(urlValidationService).validateUrl("http://example.com/service.wsdl");
    }

    @Test
    void importSoapWsdlFileHandlesException() throws Exception {
        when(soap.importFile("/tmp/service.wsdl")).thenThrow(new ClientApiException("oops"));
        assertThrowsExactly(ZapApiException.class, () -> service.importSoapWsdlFile("/tmp/service.wsdl"));
    }

    @Test
    void importOpenApiSpecFileHandlesException() throws Exception {
        when(openapi.importFile("/tmp/api.yaml", "host")).thenThrow(new ClientApiException("oops"));
        assertThrowsExactly(ZapApiException.class, () -> service.importOpenApiSpecFile("/tmp/api.yaml",
            "host"));
    }
}
