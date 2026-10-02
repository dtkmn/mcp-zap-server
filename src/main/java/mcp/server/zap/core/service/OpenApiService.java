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

import java.net.URI;
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
     * @param hostOverride Optional full HTTP(S) target or authority/path override with an explicit host
     * @return A message indicating the import status
     */
    public String importOpenApiSpec(
            String apiUrl,
            String hostOverride
    ) {
        String normalizedApiUrl = requireText(apiUrl, "apiUrl");
        urlValidationService.validateUrl(normalizedApiUrl);

        ImportResult result = engineApiImportAccess.importOpenApiUrl(
                new UrlImportRequest(normalizedApiUrl, validateOpenApiTargetOverride(hostOverride)));
        return formatImportResponse("OpenAPI import", result);
    }


    /**
     * Import an OpenAPI/Swagger spec from a local file into ZAP and return the importId.
     *
     * @param filePath     The path to the OpenAPI/Swagger spec file (JSON or YAML)
     * @param hostOverride Optional full HTTP(S) target or authority/path override with an explicit host
     * @return A message indicating the import status
     */
    public String importOpenApiSpecFile(
            String filePath,
            String hostOverride
    ) {
        String normalizedFilePath = requireText(filePath, "filePath");
        ImportResult result = engineApiImportAccess.importOpenApiFile(
                new FileImportRequest(normalizedFilePath, validateOpenApiTargetOverride(hostOverride)));
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

    private String validateOpenApiTargetOverride(String value) {
        String target = trimToNull(value);
        if (target == null) {
            // No override retains ZAP's definition-derived target resolution. The definition
            // and its references must be trusted; engine egress controls remain necessary.
            return null;
        }

        boolean hasScheme = target.contains("://");
        if (!hasScheme && target.contains("//") && !target.startsWith("//")) {
            // ZAP's lenient target parser treats // as an authority delimiter, even in a path.
            throw new IllegalArgumentException("Invalid hostOverride authority/path syntax");
        }
        String candidate = hasScheme ? target
                : target.startsWith("//") ? "http:" + target : "http://" + target;
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid hostOverride; supply an HTTP(S) target with an explicit host", e);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("hostOverride must include an explicit host; supply a full HTTP(S) target URL instead of a scheme-only or path-only override");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("hostOverride supports only HTTP and HTTPS");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("hostOverride must have a valid port and no user info, query, or fragment");
        }
        urlValidationService.validateUrl(candidate);
        if (!hasScheme) {
            // ZAP inherits HTTP or HTTPS from the definition. Check both possible destinations
            // without substituting the definition download URL for the API's server URL.
            urlValidationService.validateUrl("https:" + uri.getRawSchemeSpecificPart());
        }
        return target;
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
