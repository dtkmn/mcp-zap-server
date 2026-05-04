package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the gateway API/schema import boundary.
 */
@Slf4j
@Component
public class ZapEngineApiImportAccess implements EngineApiImportAccess {

    private final ClientApi zap;

    public ZapEngineApiImportAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public ImportResult importOpenApiUrl(UrlImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.openapi.importUrl(request.url(), request.hostOverride())));
        } catch (ClientApiException e) {
            log.error("Error importing OpenAPI spec URL {}: {}", request.url(), e.getMessage(), e);
            throw new ZapApiException("Error importing OpenAPI/Swagger spec URL", e);
        }
    }

    @Override
    public ImportResult importOpenApiFile(FileImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.openapi.importFile(request.filePath(), request.hostOverride())));
        } catch (ClientApiException e) {
            log.error("Error importing OpenAPI spec file: {}", e.getMessage(), e);
            throw new ZapApiException("Error importing OpenAPI/Swagger spec file", e);
        }
    }

    @Override
    public ImportResult importGraphqlUrl(GraphqlUrlImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.graphql.importUrl(request.endpointUrl(), request.schemaUrl())));
        } catch (ClientApiException e) {
            log.error("Error importing GraphQL schema URL {} for endpoint {}: {}",
                    request.schemaUrl(), request.endpointUrl(), e.getMessage(), e);
            throw new ZapApiException("Error importing GraphQL schema URL", e);
        }
    }

    @Override
    public ImportResult importGraphqlFile(GraphqlFileImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.graphql.importFile(request.endpointUrl(), request.filePath())));
        } catch (ClientApiException e) {
            log.error("Error importing GraphQL schema file {} for endpoint {}: {}",
                    request.filePath(), request.endpointUrl(), e.getMessage(), e);
            throw new ZapApiException("Error importing GraphQL schema file", e);
        }
    }

    @Override
    public ImportResult importSoapUrl(SoapUrlImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.soap.importUrl(request.wsdlUrl())));
        } catch (ClientApiException e) {
            log.error("Error importing SOAP/WSDL URL {}: {}", request.wsdlUrl(), e.getMessage(), e);
            throw new ZapApiException("Error importing SOAP/WSDL URL", e);
        }
    }

    @Override
    public ImportResult importSoapFile(FileOnlyImportRequest request) {
        try {
            return new ImportResult(flattenResponseValues(zap.soap.importFile(request.filePath())));
        } catch (ClientApiException e) {
            log.error("Error importing SOAP/WSDL file {}: {}", request.filePath(), e.getMessage(), e);
            throw new ZapApiException("Error importing SOAP/WSDL file", e);
        }
    }

    private List<String> flattenResponseValues(ApiResponse response) {
        List<String> values = new ArrayList<>();
        if (response == null) {
            return values;
        }

        if (response instanceof ApiResponseElement element) {
            if (hasText(element.getValue())) {
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
