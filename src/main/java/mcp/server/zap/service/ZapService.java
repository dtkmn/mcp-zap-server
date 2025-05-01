package mcp.server.zap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ZapService {

    private final ClientApi zap;

    @Value("${zap.report.template:traditional-html-plus}")
    private String reportTemplate;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    private final ObjectMapper yamlMapper = new YAMLMapper();

    private final RestTemplate restTemplate;

    public ZapService(
            @Value("${zap.server.url:localhost}") String zapApiUrl,
            @Value("${zap.server.port:8090}") int zapApiPort,
            @Value("${zap.server.apiKey:}") String zapApiKey,
            RestTemplate restTemplate
    ) {
        this.restTemplate = restTemplate;
        // Initialize ZAP client
        this.zap = new ClientApi(zapApiUrl, zapApiPort, zapApiKey);
    }

    @Tool(name = "zap_spider", description = "Start a spider scan on the given URL")
    public String startSpider(@ToolParam(description = "targetUrl") String targetUrl) throws Exception {
        try {
            new URL(targetUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL format: " + targetUrl);
        }
        String sessionName = "scan-" + System.currentTimeMillis();
//        zap.network.setConnectionTimeout("60");
        zap.core.setOptionTimeoutInSecs(60);
        zap.core.newSession(sessionName, "true");
        // (Optionally) clear the Site-Tree and alerts
//        zap.core.deleteAllAlerts("");

        // Include the target in context
        String contextName = "ctx-" + sessionName;
        zap.context.newContext(contextName);
        zap.context.includeInContext(contextName, targetUrl + ".*");

        // Force-fetch the root so it appears in the tree
        zap.core.accessUrl(targetUrl, "true");
        // Set spider options
        zap.spider.setOptionThreadCount(5);  // Limits the spider to 5 thread for a slower crawl
        ApiResponse resp = zap.spider.scan(targetUrl, "10", "true", "", "false");
        String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement)resp).getValue();
        return "Spider scan started with ID: " + scanId;
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
        String fileName = ((ApiResponseElement)raw).getValue();
        Path reportPath = Paths.get(fileName);
        if (!Files.exists(reportPath)) {
            throw new IllegalStateException("Expected report not found at " + reportPath);
        }
        return reportPath.toString();
    }
//
//    @Tool(
//            name        = "generate_api_html_doc",
//            description = "Generate a standalone HTML doc (via ReDoc) from a raw Swagger 2 or OpenAPI 3 spec (YAML or JSON)"
//    )
//    public String generateApiHtmlDoc(
//            @ToolParam(description = "The raw OpenAPI/Swagger spec (JSON or YAML)")
//            String rawSpec
//    ) {
//        List<ValidationProblem> problems = validateAPISpec(rawSpec);
//        if (!problems.isEmpty()) {
//            String issues = problems.stream()
//                    .map(p -> p.message)
//                    .collect(Collectors.joining("\n• ", "• ", ""));
//            return "Spec validation errors:\n" + issues;
//        }
//
//        // 1. Turn YAML or JSON into a Jackson tree
//        JsonNode root;
//        try {
//            root = yamlMapper.readTree(rawSpec);
//        } catch (IOException e) {
//            throw new RuntimeException("Invalid spec syntax: " + e.getMessage());
//        }
//
//        // 2. Serialize tree to pretty JSON
//        String jsonSpec;
//        try {
//            jsonSpec = new ObjectMapper()
//                    .writerWithDefaultPrettyPrinter()
//                    .writeValueAsString(root);
//        } catch (JsonProcessingException e) {
//            throw new RuntimeException("Error converting spec to JSON: " + e.getMessage());
//        }
//
//        // ── 6) Render HTML ────────────────────────────────────────
//        return """
//            <!DOCTYPE html>
//            <html>
//            <head>
//            <meta charset="utf-8"/>
//            <title>API Documentation</title>
//            <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
//            </head>
//            <body>
//            <div id="redoc-container"></div>
//            <script type="application/json" id="api-spec">
//            %s
//            </script>
//            <script>
//                const raw = document.getElementById("api-spec").textContent;
//                const spec = JSON.parse(raw);
//                Redoc.init(spec, {}, document.getElementById("redoc-container"));
//              </script>
//            </body>
//            </html>
//       \s""".formatted(jsonSpec);
//    }
//
//    @Tool(
//            name        = "validate_openapi_spec",
//            description = "Validate a Swagger 2 or OpenAPI 3 spec (JSON or YAML)"
//    )
//    public String validateOpenApiSpec(
//            @ToolParam(description = "Raw Swagger 2 or OpenAPI 3 spec content")
//            String rawSpec
//    ) throws JsonProcessingException {
//        List<ValidationProblem> problems = validateAPISpec(rawSpec);
//        if (problems.isEmpty()) {
//            return "✔️ Spec is valid";
//        }
//        String issues = problems.stream()
//            .map(p -> p.message)
//            .collect(Collectors.joining("\n• ", "• ", ""));
//        return "❌ Validation issues:\n" + issues;
//    }
//
//    private List<ValidationProblem> validateAPISpec(String rawSpec) {
//        // 1) Parse YAML or JSON into Jackson tree
//        JsonNode root;
//        try {
//            // Parse YAML/JSON into JsonNode (lenient for YAML)
//            root = yamlMapper.readTree(rawSpec);
//        } catch (IOException e) {
//            throw new RuntimeException("Error: Invalid YAML/JSON syntax: " + e.getMessage());
//        }
//
//        // Detect spec version
//        boolean isOAS3 = root.has("openapi");
//        boolean isSwagger2 = root.has("swagger");
//        if (!isOAS3 && !isSwagger2) {
//            throw new RuntimeException("Error: Missing 'openapi' or 'swagger' field. Cannot determine spec version.");
//        }
//
//        // Convert to compact JSON string
//        final ObjectMapper jsonMapper = new ObjectMapper();
//        String jsonContent;
//        try {
//            jsonContent = jsonMapper.writeValueAsString(root);
//        } catch (Exception e) {
//            throw new RuntimeException("Error converting spec to JSON: " + e.getMessage());
//        }
//
//        // Parse and validate using Apicurio Data Models
//        Document apiDoc;
//        try {
//            apiDoc = Library.readDocumentFromJSONString(jsonContent);
//        } catch (Exception e) {
//             throw new RuntimeException("Error parsing API spec: " + e.getMessage());
//        }
//
//        return Library.validate(apiDoc, null);
//    }
//
//
//    @Tool(name="validate_openapi_spec_from_url",description="Validate a Swagger 2 or OpenAPI 3 spec (JSON or YAML) from a URL")
//    public String validateSpecFromUrl(@ToolParam(description = "URL of API spec") String url) throws Exception {
//        String raw = restTemplate.getForObject(url, String.class);
//        return validateOpenApiSpec(raw);
//    }
//
////    @Tool(
////            name        = "zap_run_dynamic_plan",
////            description = "Run a full OpenAPI scan with dynamic spec URL, context and timestamped report"
////    )
////    public String runDynamicPlan(
////            @ToolParam(description = "Name of the ZAP context (e.g. petstore)") String contextName,
////            @ToolParam(description = "Base API URL to include in the context") String baseUrl,
////            @ToolParam(description = "OpenAPI spec URL (JSON or YAML)") String specUrl,
////            @ToolParam(description = "Directory inside ZAP to write the report (e.g. /zap/wrk)") String reportDir
////    ) throws Exception {
////        // 1) Build a timestamped reportFile name
////        String timestamp = LocalDateTime.now()
////                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
////        String reportFile = contextName + "-api-scan-" + timestamp + ".html";
////
////        // 2) Construct the YAML plan string
////        String yamlPlan = String.format("""
////          env:
////            parameters:
////                failOnError: false
////            contexts:
////              - name: %s
////                urls:
////                  - %s
////
////          jobs:
////            - type: openapi
////              parameters:
////                context: %s
////                apiUrl: %s
////                failOnError: false
////
////            - type: spider
////              parameters:
////                context: %s
////                maxDepth: 5
////
////            - type: activeScan
////              parameters:
////                context: %s
////                policy: Default Policy
////                maxScanDurationInMins: 10
////
////            - type: report
////              parameters:
////                template: traditional-html-plus
////                reportFile: %s
////                reportDir: %s
////          """,
////                contextName, baseUrl,
////                contextName, specUrl,
////                contextName,
////                contextName,
////                reportFile, reportDir
////        );
////
////        // 3) Write the plan to a timestamped file under /zap/wrk
////        Path saveZapWorkPath = Path.of("/zap/wrk");
////        Path planFile = Files.createTempFile(saveZapWorkPath, "zap-auto-job-", ".yaml");
////        log.info("saving plan file {}", planFile);
////        Files.writeString(planFile, yamlPlan, StandardCharsets.UTF_8);
////
////        // 4) Tell ZAP to run that plan via runPlan (filePath must be visible to ZAP)
////        ApiResponseElement resp = (ApiResponseElement) zap.callApi(
////                "automation", "action", "runPlan",
////                Map.of("filePath", "/zap/wrk/" + planFile.getFileName())
////        );
////
////        // 5) Return whatever ZAP returns (status/summary)
////        return resp.getValue();
////    }
//
//    @Tool(
//        name        = "zap_get_plan_progress",
//        description = "Get the current progress of an Automation plan by planId"
//    )
//    public String getPlanProgress(
//        @ToolParam(description = "The planId returned by zap_run_dynamic_plan or zap_run_plan") String planId
//    ) throws Exception {
//        // 1) Call the view endpoint
//        ApiResponseSet resp = (ApiResponseSet) zap.callApi(
//                "automation", "view", "planProgress",
//                Map.of("planId", planId)
//        );
//        // 2) Prepare a JSON-friendly map
//        Map<String, Object> progress = new LinkedHashMap<>();
//
//        for (Map.Entry<String, ApiResponse> e : resp.getValuesMap().entrySet()) {
//            String key = e.getKey();
//            ApiResponse value = e.getValue();
//
//            if (value instanceof ApiResponseElement elt) {
//                // Single scalar
//                progress.put(key, elt.getValue());
//
//            } else if (value instanceof ApiResponseList list) {
//                // A list of things
//                List<Object> items = new ArrayList<>();
//                for (ApiResponse item : list.getItems()) {
//                    if (item instanceof ApiResponseElement ie) {
//                        items.add(ie.getValue());
//                    } else if (item instanceof ApiResponseSet is) {
//                        items.add(is.getValuesMap());  // a nested map
//                    } else {
//                        items.add(item.toString());
//                    }
//                }
//                progress.put(key, items);
//
//            } else {
//                // Fallback for anything else
//                progress.put(key, value.toString());
//            }
//        }
//
//        // 3) Serialize to JSON
//        return new ObjectMapper()
//                .writerWithDefaultPrettyPrinter()
//                .writeValueAsString(progress);
//    }
//
//    @Tool(
//            name        = "zap_import_openapi_spec",
//            description = "Import an OpenAPI/Swagger spec by URL into ZAP and return the importId"
//    )
//    public String importOpenApiSpec(
//            @ToolParam(description = "OpenAPI/Swagger spec URL (JSON or YAML)") String apiUrl
//    ) throws Exception {
//        ApiResponse resp = zap.callApi(
//            "openapi", "action", "importUrl",
//            Map.of("url", apiUrl)
//        );
//
//        if (resp instanceof ApiResponseElement elt) {
//            // single ID returned
//            return "importId=" + elt.getValue();
//        }
//        else if (resp instanceof ApiResponseList list) {
//            // multiple IDs or messages returned
//            List<String> ids = list.getItems().stream()
//                .filter(item -> item instanceof ApiResponseElement)
//                .map(item -> ((ApiResponseElement) item).getValue())
//                .collect(Collectors.toList());
//            return "importIds=" + String.join(",", ids);
//        }
//        else {
//            // fallback: dump whatever it is
//            return resp.toString();
//        }
//    }
//
    // ─── Active Scan ─────────────────────────────────────────────────────────────
    @Tool(
            name        = "zap_active_scan",
            description = "Start an active scan against the given URL and return the scanId"
    )
    public String activeScan(
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (true/false)") String recurse,
            @ToolParam(description = "Scan policy name (e.g. Default Policy, API-High+Auth)") String policy
    ) throws Exception {
        // Configure active scanner
        zap.ascan.enableAllScanners(null);  // Enable all scanners
        
        // Configure global timeouts and scan settings
        zap.ascan.setOptionMaxScanDurationInMins(0);    // No duration limit
//        zap.ascan.setOptionTimeoutInSecs(60);           // 60 seconds per rule
        zap.ascan.setOptionHostPerScan(0);              // No limit on hosts
        zap.ascan.setOptionThreadPerHost(5);           // Parallel scanning
        zap.ascan.setOptionDelayInMs(0);               // No delay between requests
//        zap.selenium.setOptionBrowserWithoutProxyTimeout(60);  // Browser timeout

        ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
               targetUrl,
               "true",
               "true",
               null,   // method
               null,   // postData
               null    // contextId
        );

        if (!(scanResp instanceof ApiResponseElement)) {
            throw new IllegalStateException("Failed to start scan on " + targetUrl + ": " + scanResp);
        }

        String scanId = ((ApiResponseElement) scanResp).getValue();
        log.info("Started active scan with ID {} on {}", scanId, targetUrl);

        return "Active scan started with ID: " + scanId;
    }



    // ─── Generate HTML Report ────────────────────────────────────────────────────
//    @Tool(
//            name        = "zap_generate_report",
//            description = "Generate an HTML report from the current ZAP session and return the file path"
//    )
//    public String generateReport(
//            @ToolParam(description = "Report template (e.g. traditional-html, traditional-html-plus)") String template,
//            @ToolParam(description = "Output directory inside ZAP") String reportDir
//    ) throws Exception {
//        String fileName = "zap-report-" + System.currentTimeMillis() + ".html";
//        ApiResponseElement resp = (ApiResponseElement) zap.callApi(
//                "reports","action","generate",
//                Map.of(
//                        "template",       template,
//                        "reportDir",      reportDir,
//                        "reportFileName", fileName,
//                        "display",        "false"
//                )
//        );
//        String path = resp.getValue();
//        return "reportPath=" + path;
//    }

}
