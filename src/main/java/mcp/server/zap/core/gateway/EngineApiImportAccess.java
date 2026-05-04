package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing access contract for API/schema import operations.
 */
public interface EngineApiImportAccess {

    ImportResult importOpenApiUrl(UrlImportRequest request);

    ImportResult importOpenApiFile(FileImportRequest request);

    ImportResult importGraphqlUrl(GraphqlUrlImportRequest request);

    ImportResult importGraphqlFile(GraphqlFileImportRequest request);

    ImportResult importSoapUrl(SoapUrlImportRequest request);

    ImportResult importSoapFile(FileOnlyImportRequest request);

    record UrlImportRequest(String url, String hostOverride) {
    }

    record FileImportRequest(String filePath, String hostOverride) {
    }

    record GraphqlUrlImportRequest(String endpointUrl, String schemaUrl) {
    }

    record GraphqlFileImportRequest(String endpointUrl, String filePath) {
    }

    record SoapUrlImportRequest(String wsdlUrl) {
    }

    record FileOnlyImportRequest(String filePath) {
    }

    record ImportResult(List<String> values) {
        public ImportResult {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
}
