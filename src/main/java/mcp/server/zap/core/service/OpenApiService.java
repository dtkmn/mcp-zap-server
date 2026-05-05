package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.FileOnlyImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.GraphqlFileImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.GraphqlUrlImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.ImportResult;
import mcp.server.zap.core.gateway.EngineApiImportAccess.SoapUrlImportRequest;
import mcp.server.zap.core.gateway.EngineApiImportAccess.UrlImportRequest;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenApiService {

    private final EngineApiImportAccess engineApiImportAccess;
    private final UrlValidationService urlValidationService;

    /**
     * Build-time dependency injection constructor.
     */
    public OpenApiService(EngineApiImportAccess engineApiImportAccess, UrlValidationService urlValidationService) {
        this.engineApiImportAccess = engineApiImportAccess;
        this.urlValidationService = urlValidationService;
    }

    /**
     * Import an OpenAPI/Swagger spec by URL into ZAP and return the importId.
     *
     * @param apiUrl       The OpenAPI/Swagger spec URL (JSON or YAML)
     * @param hostOverride Optional host override for the API spec
     * @return A message indicating the import status
     */
    public String importOpenApiSpec(
            String apiUrl,
            String hostOverride
    ) {
        String normalizedApiUrl = requireText(apiUrl, "apiUrl");
        urlValidationService.validateUrl(normalizedApiUrl);

        ImportResult result = engineApiImportAccess.importOpenApiUrl(
                new UrlImportRequest(normalizedApiUrl, trimToNull(hostOverride)));
        return formatImportResponse("OpenAPI import", result);
    }


    /**
     * Import an OpenAPI/Swagger spec from a local file into ZAP and return the importId.
     *
     * @param filePath     The path to the OpenAPI/Swagger spec file (JSON or YAML)
     * @param hostOverride Optional host override for the API spec
     * @return A message indicating the import status
     */
    public String importOpenApiSpecFile(
            String filePath,
            String hostOverride
    ) {
        String normalizedFilePath = requireText(filePath, "filePath");
        ImportResult result = engineApiImportAccess.importOpenApiFile(
                new FileImportRequest(normalizedFilePath, trimToNull(hostOverride)));
        return formatImportResponse("OpenAPI import", result);
    }

    public String importGraphqlSchemaUrl(
            String endpointUrl,
            String schemaUrl
    ) {
        String normalizedEndpointUrl = requireText(endpointUrl, "endpointUrl");
        String normalizedSchemaUrl = requireText(schemaUrl, "schemaUrl");
        urlValidationService.validateUrl(normalizedEndpointUrl);
        urlValidationService.validateUrl(normalizedSchemaUrl);

        ImportResult result = engineApiImportAccess.importGraphqlUrl(
                new GraphqlUrlImportRequest(normalizedEndpointUrl, normalizedSchemaUrl));
        return formatImportResponse("GraphQL import", result);
    }

    public String importGraphqlSchemaFile(
            String endpointUrl,
            String filePath
    ) {
        String normalizedEndpointUrl = requireText(endpointUrl, "endpointUrl");
        String normalizedFilePath = requireText(filePath, "filePath");
        urlValidationService.validateUrl(normalizedEndpointUrl);

        ImportResult result = engineApiImportAccess.importGraphqlFile(
                new GraphqlFileImportRequest(normalizedEndpointUrl, normalizedFilePath));
        return formatImportResponse("GraphQL import", result);
    }

    public String importSoapWsdlUrl(
            String wsdlUrl
    ) {
        String normalizedWsdlUrl = requireText(wsdlUrl, "wsdlUrl");
        urlValidationService.validateUrl(normalizedWsdlUrl);

        ImportResult result = engineApiImportAccess.importSoapUrl(new SoapUrlImportRequest(normalizedWsdlUrl));
        return formatImportResponse("SOAP/WSDL import", result);
    }

    public String importSoapWsdlFile(
            String filePath
    ) {
        String normalizedFilePath = requireText(filePath, "filePath");
        ImportResult result = engineApiImportAccess.importSoapFile(new FileOnlyImportRequest(normalizedFilePath));
        return formatImportResponse("SOAP/WSDL import", result);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String formatImportResponse(String importFamily, ImportResult importResult) {
        java.util.List<String> values = importResult.values();
        if (values.isEmpty()) {
            return importFamily + " completed and is ready to scan.";
        }

        if (values.stream().allMatch(this::looksNumeric)) {
            return importFamily + " completed asynchronously (jobs: " + String.join(",", values) + ") and is ready to scan.";
        }

        return importFamily + " completed with messages: "
                + values.stream().collect(Collectors.joining(" | "))
                + ". It is ready to scan.";
    }

    private boolean looksNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }
}
