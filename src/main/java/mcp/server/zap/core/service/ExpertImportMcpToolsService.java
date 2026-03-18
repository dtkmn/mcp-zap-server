package mcp.server.zap.core.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for raw API schema import tools.
 */
@Service
public class ExpertImportMcpToolsService implements ExpertToolGroup {
    private final OpenApiService openApiService;

    public ExpertImportMcpToolsService(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @Tool(
            name = "zap_import_openapi_spec_url",
            description = "Import an OpenAPI/Swagger spec by URL into ZAP and return the importId"
    )
    public String importOpenApiSpec(
            @ToolParam(description = "OpenAPI/Swagger spec URL") String apiUrl,
            @ToolParam(description = "Host override for the API spec (optional)") String hostOverride
    ) {
        return openApiService.importOpenApiSpec(apiUrl, hostOverride);
    }

    @Tool(
            name = "zap_import_openapi_spec_file",
            description = "Import an OpenAPI/Swagger spec (JSON or YAML) from a local file into ZAP and return the importId"
    )
    public String importOpenApiSpecFile(
            @ToolParam(description = "Path to the OpenAPI/Swagger spec file (JSON or YAML)") String filePath,
            @ToolParam(description = "Host override for the API spec") String hostOverride
    ) {
        return openApiService.importOpenApiSpecFile(filePath, hostOverride);
    }

    @Tool(
            name = "zap_import_graphql_schema_url",
            description = "Import a GraphQL schema by URL into ZAP for a specific GraphQL endpoint and prepare it for scanning"
    )
    public String importGraphqlSchemaUrl(
            @ToolParam(description = "GraphQL endpoint URL") String endpointUrl,
            @ToolParam(description = "GraphQL schema URL to import") String schemaUrl
    ) {
        return openApiService.importGraphqlSchemaUrl(endpointUrl, schemaUrl);
    }

    @Tool(
            name = "zap_import_graphql_schema_file",
            description = "Import a GraphQL schema from a local file into ZAP for a specific GraphQL endpoint and prepare it for scanning"
    )
    public String importGraphqlSchemaFile(
            @ToolParam(description = "GraphQL endpoint URL") String endpointUrl,
            @ToolParam(description = "Path to a local GraphQL schema file") String filePath
    ) {
        return openApiService.importGraphqlSchemaFile(endpointUrl, filePath);
    }

    @Tool(
            name = "zap_import_soap_wsdl_url",
            description = "Import a SOAP/WSDL definition by URL into ZAP and prepare it for scanning"
    )
    public String importSoapWsdlUrl(@ToolParam(description = "SOAP/WSDL URL to import") String wsdlUrl) {
        return openApiService.importSoapWsdlUrl(wsdlUrl);
    }

    @Tool(
            name = "zap_import_soap_wsdl_file",
            description = "Import a SOAP/WSDL definition from a local file into ZAP and prepare it for scanning"
    )
    public String importSoapWsdlFile(@ToolParam(description = "Path to a local SOAP/WSDL file") String filePath) {
        return openApiService.importSoapWsdlFile(filePath);
    }
}
