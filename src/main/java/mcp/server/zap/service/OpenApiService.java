package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.exception.ZapApiException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.*;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OpenApiService {

    private final ClientApi zap;
    private final UrlValidationService urlValidationService;

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
    @Tool(
            name        = "zap_import_openapi_spec_url",
            description = "Import an OpenAPI/Swagger spec by URL into ZAP and return the importId"
    )
    public String importOpenApiSpec(
            @ToolParam(description = "OpenAPI/Swagger spec URL (JSON or YAML, e.g., http://example.com/openapi.yaml)") String apiUrl,
            @ToolParam(description = "Host override for the API spec (optional)") String hostOverride
    ) {
        // Validate URL before importing
        urlValidationService.validateUrl(apiUrl);

        try {
            ApiResponse importResp = zap.openapi.importUrl(apiUrl, hostOverride);
            List<String> importIds = new ArrayList<>();
            if (importResp instanceof ApiResponseList list) {
                for (ApiResponse item : list.getItems()) {
                    if (item instanceof ApiResponseElement elt) {
                        importIds.add(elt.getValue());
                    }
                }
            }
            return importIds.isEmpty()
                    ? "Import completed synchronously and is ready to scan."
                    : "Import completed asynchronously (jobs: " + String.join(",", importIds) + ") and is ready to scan.";
        } catch (ClientApiException e) {
            log.error("Failed to retrieve URLs for base URL: {}: {}", apiUrl, e.getMessage(), e);
            throw new ZapApiException("Failed to retrieve URLs", e);
        }
    }


    /**
     * Import an OpenAPI/Swagger spec from a local file into ZAP and return the importId.
     *
     * @param filePath     The path to the OpenAPI/Swagger spec file (JSON or YAML)
     * @param hostOverride Optional host override for the API spec
     * @return A message indicating the import status
     */
    @Tool(
            name = "zap_import_openapi_spec_file",
            description = "Import an OpenAPI/Swagger spec (JSON or YAML) from a local file into ZAP and return the importId"
    )
    public String importOpenApiSpecFile(
            @ToolParam(description = "Path to the OpenAPI/Swagger spec file (JSON or YAML)") String filePath,
            @ToolParam(description = "Host override for the API spec") String hostOverride
    ) {
        try {
            ApiResponse importResp = zap.openapi.importFile(filePath, hostOverride);
            List<String> importIds = new ArrayList<>();
            if (importResp instanceof ApiResponseList list) {
                for (ApiResponse item : list.getItems()) {
                    if (item instanceof ApiResponseElement elt) {
                        importIds.add(elt.getValue());
                    }
                }
            }
            return importIds.isEmpty()
                    ? "Import completed synchronously and is ready to scan."
                    : "Import completed asynchronously (jobs: " + String.join(",", importIds) + ") and is ready to scan.";
        } catch (ClientApiException e) {
            log.error("Error importing OpenAPI spec file: {}", e.getMessage(), e);
            throw new ZapApiException("Error importing OpenAPI/Swagger spec file", e);
        }
    }

}
