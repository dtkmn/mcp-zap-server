package mcp.server.zap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.apicurio.datamodels.Library;
import io.apicurio.datamodels.models.Document;
import io.apicurio.datamodels.validation.ValidationProblem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.zaproxy.clientapi.core.*;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ZapService {

    private final ClientApi zap;
    private final String contextName = "default-context";
    private final String sessionName = "default-session";

    @Value("${zap.report.template:traditional-html-plus}")
    private String reportTemplate;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    private final ObjectMapper yamlMapper = new YAMLMapper();

    private final RestTemplate restTemplate;

    public ZapService(ClientApi zap,
                      RestTemplate restTemplate) throws ClientApiException {
        this.restTemplate = restTemplate;
        this.zap = zap;
    }

    @Tool(name = "zap_spider", description = "Start a spider scan on the given URL")
    public String startSpider(@ToolParam(description = "targetUrl") String targetUrl) throws Exception {
        try {
            new URL(targetUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + targetUrl);
        }

        try {

            zap.core.newSession(sessionName, "true");
            zap.context.newContext(contextName);

            String sessionName = "scan-" + System.currentTimeMillis();
//        zap.network.setConnectionTimeout("60");
            zap.core.setOptionTimeoutInSecs(60);
            zap.context.includeInContext(contextName, targetUrl + ".*");

            // Force-fetch the root so it appears in the tree
            zap.core.accessUrl(targetUrl, "true");
            // Set spider options
            zap.spider.setOptionThreadCount(5);  // Limits the spider to 5 thread for a slower crawl
            ApiResponse resp = zap.spider.scan(targetUrl, "10", "true", "", "false");
            String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
            return "Spider scan started with ID: " + scanId;
        } catch (Exception e) {
            log.error("Error launching ZAP Spider for URL {}: {}", targetUrl, e.getMessage(), e);
            return "❌ Error launching spider: " + e.getMessage();
        }
    }

    @Tool(name = "zap_spider_status", description = "Get status of a spider scan by ID")
    public String getSpiderStatus(@ToolParam(description = "scanId") String scanId) throws Exception {
        ApiResponse resp = zap.spider.status(scanId);
        return ((org.zaproxy.clientapi.core.ApiResponseElement)resp).getValue();
    }

    @Tool(name = "zap_alerts", description = "Retrieve alerts for the given base URL")
    public List<String> getAlerts(@ToolParam(description = "baseUrl") String baseUrl) throws Exception {
//        ApiResponse resp = zap.core.alerts(baseUrl, null, null);
//        return ((org.zaproxy.clientapi.core.ApiResponseList)resp).getItems().stream()
//                .map(r -> ((org.zaproxy.clientapi.core.ApiResponseSet)r).getStringValue("alert"))
//                .collect(Collectors.toList());
        // Request all alerts for the site (start=0, count=-1 means all)
        String start = "0";
        String count = "-1";
        ApiResponseList resp = (ApiResponseList) zap.core.alerts(
                baseUrl != null ? baseUrl : "", start, count
        );

        // Build a list of human-readable alert summaries
        List<String> alerts = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            ApiResponseSet set = (ApiResponseSet) item;
            String name = set.getStringValue("alert");
            String risk = set.getStringValue("risk");
            String url  = set.getStringValue("url");
            alerts.add(String.format("%s (risk: %s) at %s", name, risk, url));
        }
        return alerts;

    }

    @Tool(name="zap_get_html_report",
            description="Generate the full session ZAP scan report in HTML format, return the path to the file")
    public String getHtmlReport() throws Exception {
        try {
            ApiResponse raw = zap.reports.generate(
                    "My ZAP Scan Report",          // title
                    "traditional-html-plus",       // template ID
                    "dark",                        // theme
                    "",                            // description
                    "",                            // contexts
                    "",                            // sites
                    "",                            // sections
                    "",                            // includedConfidences
                    "",                            // includedRisks
                    "zap-report-" + System.currentTimeMillis() + ".html",
                    "",                            // reportFileNamePattern
                    reportDirectory,
                    "false"                        // display=false means “don’t pop open a browser”
            );
            if (!(raw instanceof ApiResponseElement)) {
                throw new IllegalStateException("Report generation failed: " + raw);
            }
            String fileName = ((ApiResponseElement) raw).getValue();
            Path reportPath = Paths.get(fileName);
            return reportPath.toString();
        } catch (Exception e) {
            log.error("Error generating ZAP report: {}", e.getMessage(), e);
            return "❌ Error generating report: " + e.getMessage();
        }
    }

    @Tool(
            name        = "validate_openapi_spec",
            description = "Validate a Swagger 2 or OpenAPI 3 spec (JSON or YAML)"
    )
    public String validateOpenApiSpec(
            @ToolParam(description = "Raw Swagger 2 or OpenAPI 3 spec content")
            String rawSpec) {
        List<ValidationProblem> problems = validateAPISpec(rawSpec);
        if (problems.isEmpty()) {
            return "✔️ Spec is valid";
        }
        String issues = problems.stream()
            .map(p -> p.message)
            .collect(Collectors.joining("\n• ", "• ", ""));
        return "❌ Validation issues:\n" + issues;
    }

    private List<ValidationProblem> validateAPISpec(String rawSpec) {
        // 1) Parse YAML or JSON into a Jackson tree
        JsonNode root;
        try {
            // Parse YAML/JSON into JsonNode (lenient for YAML)
            root = yamlMapper.readTree(rawSpec);
        } catch (IOException e) {
            throw new RuntimeException("Error: Invalid YAML/JSON syntax: " + e.getMessage());
        }

        // Detect spec version
        boolean isOAS3 = root.has("openapi");
        boolean isSwagger2 = root.has("swagger");
        if (!isOAS3 && !isSwagger2) {
            throw new RuntimeException("Error: Missing 'openapi' or 'swagger' field. Cannot determine spec version.");
        }

        // Convert to compact JSON string
        final ObjectMapper jsonMapper = new ObjectMapper();
        String jsonContent;
        try {
            jsonContent = jsonMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Error converting spec to JSON: " + e.getMessage());
        }

        // Parse and validate using Apicurio Data Models
        Document apiDoc;
        try {
            apiDoc = Library.readDocumentFromJSONString(jsonContent);
        } catch (Exception e) {
             throw new RuntimeException("Error parsing API spec: " + e.getMessage());
        }

        return Library.validate(apiDoc, null);
    }


    @Tool(name="validate_openapi_spec_from_url",description="Validate a Swagger 2 or OpenAPI 3 spec (JSON or YAML) from a URL")
    public String validateSpecFromUrl(@ToolParam(description = "URL of API spec") String url) throws Exception {
        String raw = restTemplate.getForObject(url, String.class);
        return validateOpenApiSpec(raw);
    }

    @Tool(
        name        = "zap_import_openapi_spec",
        description = "Import an OpenAPI/Swagger spec by URL into ZAP and return the importId"
    )
    public String importOpenApiSpec(
        @ToolParam(description = "OpenAPI/Swagger spec URL (JSON or YAML)") String apiUrl
    ) throws Exception {
        // 1. Validate the URL
        try {
            new URL(apiUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL: " + apiUrl, e);
        }

        zap.core.newSession(sessionName, "true");
        zap.context.newContext(contextName);

        // 2. Import OpenAPI spec
        ApiResponse importResp = zap.callApi(
                "openapi",
                "action",
                "importUrl",
                Map.of("url", apiUrl)
        );

        List<String> importIds = new ArrayList<>();
        if (importResp instanceof ApiResponseList list) {
            for (ApiResponse item : list.getItems()) {
                if (item instanceof ApiResponseElement elt) {
                    importIds.add(elt.getValue());
                }
            }
        }

        // 3. If import IDs exist, poll their status
        for (String id : importIds) {
            int status;
            do {
                Thread.sleep(500);
                ApiResponse statusResp = zap.callApi("openapi", "action", "status", Map.of("importId", id));
                if (!(statusResp instanceof ApiResponseElement elt)) {
                    throw new IllegalStateException("Unexpected status response: " + statusResp);
                }
                status = Integer.parseInt(elt.getValue());
            } while (status != 1); // wait until status is completed (1)
        }

        // 4. Confirm import succeeded by checking Site-Tree
        ApiResponseList sitesResp = (ApiResponseList) zap.core.sites();
        boolean found = false;
        for (ApiResponse siteItem : sitesResp.getItems()) {
            String site = ((ApiResponseElement) siteItem).getValue();
            if (apiUrl.startsWith(site) || site.startsWith(apiUrl)) {
                found = true;
                break;
            }
        }

        if (!found) {
            throw new IllegalStateException("Spec imported but no URLs found under site-tree for: " + apiUrl);
        }

        return importIds.isEmpty()
                ? "Import completed synchronously and is ready to scan."
                : "Import completed asynchronously (jobs: " + String.join(",", importIds) + ") and is ready to scan.";
    }

    @Tool(
            name        = "zap_stop_all_scans",
            description = "Stop all running Active Scans in this ZAP session"
    )
    public String stopAllScans() throws Exception {
        zap.ascan.stopAllScans();
        return "🛑 All active scans have been stopped.";
    }

}
