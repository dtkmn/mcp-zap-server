package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenApiService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;

    /**
     * Build-time dependency injection constructor.
     */
    public OpenApiService(ClientApi zap, UrlValidationService urlValidationService) {
        this.zap = zap;
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

        try {
            ApiResponse importResp = zap.openapi.importUrl(normalizedApiUrl, trimToNull(hostOverride));
            return formatImportResponse("OpenAPI import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing OpenAPI spec URL {}: {}", normalizedApiUrl, e.getMessage(), e);
            throw new ZapApiException("Error importing OpenAPI/Swagger spec URL", e);
        }
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
        try {
            ApiResponse importResp = zap.openapi.importFile(normalizedFilePath, trimToNull(hostOverride));
            return formatImportResponse("OpenAPI import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing OpenAPI spec file: {}", e.getMessage(), e);
            throw new ZapApiException("Error importing OpenAPI/Swagger spec file", e);
        }
    }

    public String importGraphqlSchemaUrl(
            String endpointUrl,
            String schemaUrl
    ) {
        String normalizedEndpointUrl = requireText(endpointUrl, "endpointUrl");
        String normalizedSchemaUrl = requireText(schemaUrl, "schemaUrl");
        urlValidationService.validateUrl(normalizedEndpointUrl);
        urlValidationService.validateUrl(normalizedSchemaUrl);

        try {
            ApiResponse importResp = zap.graphql.importUrl(normalizedEndpointUrl, normalizedSchemaUrl);
            return formatImportResponse("GraphQL import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing GraphQL schema URL {} for endpoint {}: {}", normalizedSchemaUrl, normalizedEndpointUrl, e.getMessage(), e);
            throw new ZapApiException("Error importing GraphQL schema URL", e);
        }
    }

    public String importGraphqlSchemaFile(
            String endpointUrl,
            String filePath
    ) {
        String normalizedEndpointUrl = requireText(endpointUrl, "endpointUrl");
        String normalizedFilePath = requireText(filePath, "filePath");
        urlValidationService.validateUrl(normalizedEndpointUrl);

        try {
            ApiResponse importResp = zap.graphql.importFile(normalizedEndpointUrl, normalizedFilePath);
            return formatImportResponse("GraphQL import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing GraphQL schema file {} for endpoint {}: {}", normalizedFilePath, normalizedEndpointUrl, e.getMessage(), e);
            throw new ZapApiException("Error importing GraphQL schema file", e);
        }
    }

    public String importSoapWsdlUrl(
            String wsdlUrl
    ) {
        String normalizedWsdlUrl = requireText(wsdlUrl, "wsdlUrl");
        urlValidationService.validateUrl(normalizedWsdlUrl);

        try {
            ApiResponse importResp = zap.soap.importUrl(normalizedWsdlUrl);
            return formatImportResponse("SOAP/WSDL import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing SOAP/WSDL URL {}: {}", normalizedWsdlUrl, e.getMessage(), e);
            throw new ZapApiException("Error importing SOAP/WSDL URL", e);
        }
    }

    public String importSoapWsdlFile(
            String filePath
    ) {
        String normalizedFilePath = requireText(filePath, "filePath");
        try {
            ApiResponse importResp = zap.soap.importFile(normalizedFilePath);
            return formatImportResponse("SOAP/WSDL import", importResp);
        } catch (ClientApiException e) {
            log.error("Error importing SOAP/WSDL file {}: {}", normalizedFilePath, e.getMessage(), e);
            throw new ZapApiException("Error importing SOAP/WSDL file", e);
        }
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

    private String formatImportResponse(String importFamily, ApiResponse importResp) {
        List<String> values = flattenResponseValues(importResp);
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

    private List<String> flattenResponseValues(ApiResponse response) {
        List<String> values = new ArrayList<>();
        if (response == null) {
            return values;
        }

        if (response instanceof ApiResponseElement element) {
            if (element.getValue() != null && !element.getValue().isBlank()) {
                values.add(element.getValue().trim());
            }
            return values;
        }

        if (response instanceof ApiResponseList list) {
            for (ApiResponse item : list.getItems()) {
                values.addAll(flattenResponseValues(item));
            }
            return values;
        }

        if (response instanceof ApiResponseSet set) {
            for (Map.Entry<String, ApiResponse> entry : set.getValuesMap().entrySet()) {
                List<String> nestedValues = flattenResponseValues(entry.getValue());
                if (nestedValues.isEmpty()) {
                    values.add(entry.getKey());
                } else {
                    for (String nestedValue : nestedValues) {
                        values.add(entry.getKey() + "=" + nestedValue);
                    }
                }
            }
            return values;
        }

        String rendered = response.toString(0).trim();
        if (!rendered.isEmpty()) {
            values.add(rendered);
        }
        return values;
    }

    private boolean looksNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }
}
