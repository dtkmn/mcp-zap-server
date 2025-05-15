package mcp.server.zap.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OpenApiService {

    private final ClientApi zap;
    private final String contextName = "default-context";
    private final String sessionName = "default-session";

    public OpenApiService(ClientApi zap) {
        this.zap = zap;
    }

    /**
     * Import an OpenAPI/Swagger spec by URL into ZAP and return the importId.
     *
     * @param apiUrl       The OpenAPI/Swagger spec URL (JSON or YAML)
     * @param hostOverride Optional host override for the API spec
     * @return A message indicating the import status
     * @throws Exception If an error occurs during the import process
     */
    @Tool(
            name        = "zap_import_openapi_spec_url",
            description = "Import an OpenAPI/Swagger spec by URL into ZAP and return the importId"
    )
    public String importOpenApiSpec(
            @ToolParam(description = "OpenAPI/Swagger spec URL (JSON or YAML)") String apiUrl,
            @ToolParam(description = "Host override for the API spec") String hostOverride
    ) throws Exception {
        try {
            new URL(apiUrl);
        } catch (MalformedURLException e) {
            log.error("Invalid URL: {}", apiUrl, e);
            return "❌ Invalid URL: " + apiUrl;
        }

        zap.core.newSession(sessionName, "true");
        zap.context.newContext(contextName);

        ApiResponse importResp =  zap.openapi.importUrl(apiUrl, hostOverride);

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
    }


    @Tool(
            name = "zap_import_openapi_spec_file",
            description = "Import an OpenAPI/Swagger spec (JSON or YAML) from a local file into ZAP and return the importId"
    )
    public String importOpenApiSpecFile(
            @ToolParam(description = "Path to the OpenAPI/Swagger spec file (JSON or YAML)") String filePath,
            @ToolParam(description = "Host override for the API spec") String hostOverride
    ) {
        try {
            zap.core.newSession(sessionName, "true");
            zap.context.newContext(contextName);

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
        } catch (Exception e) {
            log.error("Error importing OpenAPI spec file: {}", e.getMessage(), e);
            return "❌ Error importing OpenAPI spec file: " + e.getMessage();
        }
    }

}
