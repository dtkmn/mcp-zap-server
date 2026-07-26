package mcp.server.zap.core.service;

import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileOnlyImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.GraphqlFileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.GraphqlUrlImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.ImportResult;
import mcp.server.zap.core.gateway.EngineApiImportAccess.SoapUrlImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.UrlImportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OpenApiServiceTest {
    private EngineApiImportAccess importAccess;
    private OpenApiService service;
    private UrlValidationService urlValidationService;

    @BeforeEach
    void setup() {
        importAccess = mock(EngineApiImportAccess.class);
        urlValidationService = mock(UrlValidationService.class);
        service = new OpenApiService(importAccess, urlValidationService);
    }

    @Test
    void importOpenApiSpecReturnsAsyncMessage() {
        when(importAccess.importOpenApiUrl(new UrlImportRequest("http://example.com/api.yaml", "host")))
                .thenReturn(new ImportResult(java.util.List.of("9")));

        String result = service.importOpenApiSpec("http://example.com/api.yaml", "host");

        assertTrue(result.contains("jobs: 9"));
        verify(urlValidationService).validateUrl("http://example.com/api.yaml");
    }

    @Test
    void importGraphqlSchemaUrlReturnsMessageAndValidatesBothUrls() {
        when(importAccess.importGraphqlUrl(new GraphqlUrlImportRequest(
                "http://example.com/graphql",
                "http://example.com/schema.graphql"
        ))).thenReturn(new ImportResult(java.util.List.of("Imported GraphQL schema with warnings")));

        String result = service.importGraphqlSchemaUrl("http://example.com/graphql", "http://example.com/schema.graphql");

        assertTrue(result.contains("GraphQL import completed with messages"));
        assertTrue(result.contains("Imported GraphQL schema with warnings"));
        verify(urlValidationService).validateUrl("http://example.com/graphql");
        verify(urlValidationService).validateUrl("http://example.com/schema.graphql");
    }

    @Test
    void importOpenApiSpecFileBuildsRequestAndReturnsSuccessMessage() {
        FileImportRequest request = new FileImportRequest("/tmp/api.yaml", "api.example.com");
        when(importAccess.importOpenApiFile(request)).thenReturn(new ImportResult(java.util.List.of()));

        String result = service.importOpenApiSpecFile(" /tmp/api.yaml ", " api.example.com ");

        assertTrue(result.contains("OpenAPI import completed and is ready to scan"));
        verify(importAccess).importOpenApiFile(request);
    }

    @Test
    void importGraphqlSchemaFileValidatesEndpointAndBuildsRequest() {
        GraphqlFileImportRequest request =
                new GraphqlFileImportRequest("http://example.com/graphql", "/tmp/schema.graphql");
        when(importAccess.importGraphqlFile(request))
                .thenReturn(new ImportResult(java.util.List.of("GraphQL schema imported")));

        String result = service.importGraphqlSchemaFile(
                " http://example.com/graphql ",
                " /tmp/schema.graphql "
        );

        assertTrue(result.contains("GraphQL schema imported"));
        verify(urlValidationService).validateUrl("http://example.com/graphql");
        verify(importAccess).importGraphqlFile(request);
    }

    @Test
    void importSoapWsdlUrlReturnsSyncMessage() {
        when(importAccess.importSoapUrl(new SoapUrlImportRequest("http://example.com/service.wsdl")))
                .thenReturn(new ImportResult(java.util.List.of("OK")));

        String result = service.importSoapWsdlUrl("http://example.com/service.wsdl");

        assertTrue(result.contains("SOAP/WSDL import completed with messages"));
        assertTrue(result.contains("OK"));
        verify(urlValidationService).validateUrl("http://example.com/service.wsdl");
    }

    @Test
    void importSoapWsdlFileBuildsRequestAndReturnsSuccessMessage() {
        FileOnlyImportRequest request = new FileOnlyImportRequest("/tmp/service.wsdl");
        when(importAccess.importSoapFile(request)).thenReturn(new ImportResult(java.util.List.of("OK")));

        String result = service.importSoapWsdlFile(" /tmp/service.wsdl ");

        assertTrue(result.contains("SOAP/WSDL import completed with messages"));
        verify(importAccess).importSoapFile(request);
    }

}
