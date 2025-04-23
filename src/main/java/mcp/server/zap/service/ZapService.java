package mcp.server.zap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.swagger.models.Swagger;
import io.swagger.parser.SwaggerParser;
import io.swagger.parser.util.SwaggerDeserializationResult;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ZapService {

    private final ClientApi zap;

    @Value("${zap.report.template:traditional-html-plus}")
    private String reportTemplate;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory;

    private final RestTemplate restTemplate;

    public ZapService(
            @Value("${zap.server.url:localhost}") String zapApiUrl,
            @Value("${zap.server.port:8090}") int zapApiPort,
            @Value("${zap.server.apiKey:}") String zapApiKey,
            RestTemplate restTemplate
    ) {
        // Initialize ZAP client
        this.zap = new ClientApi(zapApiUrl, zapApiPort, zapApiKey);
        this.restTemplate = restTemplate;
    }

    @Tool(name = "zap_spider", description = "Start a spider scan on the given URL")
    public String startSpider(@ToolParam(description = "targetUrl") String targetUrl) throws Exception {
        ApiResponse resp = zap.spider.scan(targetUrl, null, null, null, null);
        String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement)resp).getValue();
        return "Spider scan started with ID: " + scanId;
    }

    @Tool(name = "zap_spider_status", description = "Get status of a spider scan by ID")
    public String getSpiderStatus(@ToolParam(description = "scanId") String scanId) throws Exception {
        ApiResponse resp = zap.spider.status(scanId);
        return ((org.zaproxy.clientapi.core.ApiResponseElement)resp).getValue();
    }

    @Tool(name = "zap_alerts", description = "Retrieve alerts for base URL")
    public List<String> getAlerts(@ToolParam(description = "baseUrl") String baseUrl) throws Exception {
        ApiResponse resp = zap.core.alerts(baseUrl, null, null);
        return ((org.zaproxy.clientapi.core.ApiResponseList)resp).getItems().stream()
                .map(r -> ((org.zaproxy.clientapi.core.ApiResponseSet)r).getStringValue("alert"))
                .collect(Collectors.toList());
    }

    /**
     * Imports an OpenAPI specification from a URL and initiates an active scan.
     *
     * @param specUrl The URL of the OpenAPI specification (JSON or YAML format)
     * @return A string containing the import ID and active scan ID
     * @throws IllegalArgumentException if the spec is invalid or cannot be parsed
     * @throws Exception if there are issues with ZAP operations or network connectivity
     */
    @Tool(name = "zap_import_spec_and_scan", description = "Import an OpenAPI spec file from the given URL and perform an active scan against it")
    public String importSpecAndScan(@ToolParam(description = "specUrl") String specUrl) throws Exception {
        Objects.requireNonNull(specUrl, "specUrl cannot be null");
        log.info("[importSpecAndScan] called with URL: {}", specUrl);

        try {
            // 1. Fetch the spec content from the URL
            String specContent = restTemplate.getForObject(specUrl, String.class);
            if (specContent == null || specContent.isBlank()) {
                throw new IllegalArgumentException("Failed to fetch spec content from URL: " + specUrl);
            }
            log.debug("[importSpecAndScan] fetched {} characters of spec", specContent.length());

            // 2. Validate using Swagger Parser
            SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(specContent, null, null);
            if (parseResult.getMessages() != null && !parseResult.getMessages().isEmpty()) {
                log.error("[importSpecAndScan] spec validation errors: {}", parseResult.getMessages());
                throw new IllegalArgumentException(
                        "Invalid OpenAPI spec: " + String.join("; ", parseResult.getMessages())
                );
            }
            log.info("[importSpecAndScan] validation passed");

            // 3. Import validated spec into ZAP
            ApiResponseElement importResp = (ApiResponseElement) zap.callApi(
                    "openapi", "action", "importUrl",
                    Map.of(
                            "url", specUrl,
                            "hostOverride", (String) null,
                            "contextId",  (String) null,
                            "userId",     (String) null
                    )
            );
            String importId = importResp.getValue();
            log.info("[importSpecAndScan] importUrl returned ID: {}", importResp.getValue());

            // 4. Kick off an active scan against the imported spec
            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                    specUrl,    // target URL
                    "true",     // recurse
                    null,       // default scan policy
                    null,       // any HTTP method
                    null,       // no postData
                    null        // no context ID
            );
            String scanId = scanResp.getValue();
            log.info("[importSpecAndScan] ascan.scan returned ID: {}", scanResp.getValue());

            return String.format(
                    "Spec fetched & validated; import ID=%s; active scan ID=%s",
                    importId, scanId
            );
        } catch (Exception e) {
            throw new Exception("Error importing spec and scanning: " + e.getMessage(), e);
        }
    }

    @Tool(
            name        = "zap_import_spec_upload_and_scan",
            description = "Import an OpenAPI spec file (Base64-encoded) and perform an active scan against it"
    )
    public String importSpecUploadAndScan(
            @ToolParam(description = "Base64-encoded OpenAPI spec file contents")
            String specFileBase64
    ) throws Exception {
        Path tempFile = null;
        try {
            // 1) Decode Base64 payload and write to temp file
            byte[] decoded = Base64.getDecoder().decode(specFileBase64);
            tempFile = Files.createTempFile("zap-spec-upload", ".json");
            Files.write(tempFile, decoded);

            // 2) Import the spec file into ZAP via openapi/importFile
            ApiResponseElement importResp = (ApiResponseElement) zap.callApi(
                    "openapi", "action", "importFile",
                    Map.of("file", tempFile.toAbsolutePath().toString())
            );
            String importId = importResp.getValue();

            // 3) Start active scan against the imported spec's base URL
            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                    tempFile.toUri().toString(),  // target URL
                    "true",                       // recurse
                    null,                         // default scan policy
                    null,                         // any method
                    null,                         // no postData
                    null                          // no context ID
            );
            String scanId = scanResp.getValue();

            // 4) Return both IDs
            return String.format(
                    "Uploaded spec, importId=%s, activeScanId=%s",
                    importId, scanId
            );
        } finally {
            // Clean up temporary file
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temporary file: {}", tempFile, e);
                }
            }
        }
    }

    @Tool(name="zap_get_html_report",
            description="Generate the full session ZAP scan report in HTML format, return the path to the file")
    public String getHtmlReport() throws Exception {
        Map<String, String> params = Map.of(
                "title",           "My ZAP Scan Report",
                "template",        reportTemplate,
                "reportFileName",  "zap-scan-report-" + System.currentTimeMillis() + ".html",
                "reportDir",       reportDirectory,
                "display",         "false"
        );
        ApiResponse raw = zap.callApi(
                "reports",    // component
                "action",     // type
                "generate",   // endpoint
                params
        );
        ApiResponseElement elem = (ApiResponseElement) raw;

        // 2. Read the file contents back into a String
        Path path = Path.of(elem.getValue());

        // 3. (Optional) Clean up the temp file if desired
        // Files.delete(path);

        return path.toString();
//        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Tool(
            name        = "generate_api_html_doc_from_base64",
            description = "Generate a standalone HTML docs page for a Base64‑encoded OpenAPI/Swagger spec (v2 or v3)"
    )
    public String generateApiHtmlDocFromBase64(
            @ToolParam(description = "Base64‑encoded OpenAPI/Swagger spec content")
            String specBase64
    ) throws Exception {
        // 1) Decode Base64 → raw text (YAML or JSON)
        byte[] decoded = Base64.getDecoder().decode(specBase64);
        String content = new String(decoded, StandardCharsets.UTF_8).trim();

        // 2) Parse into JsonNode (YAMLMapper handles both YAML & JSON)
        ObjectMapper yamlReader = new YAMLMapper();
        JsonNode root = yamlReader.readTree(content);

        // 3) Serialize to canonical JSON
        String jsonSpec = new ObjectMapper().writeValueAsString(root);

        // 4) Version detection & validation using JSON input
        if (root.has("openapi")) {
            // OpenAPI 3.x
            SwaggerParseResult res = new OpenAPIV3Parser()
                    .readContents(jsonSpec, null, null);    // <-- feed JSON here
            if (res.getMessages() != null && !res.getMessages().isEmpty()) {
                throw new IllegalArgumentException("OpenAPI v3 errors: "
                        + String.join("; ", res.getMessages()));
            }
        } else if (root.has("swagger")) {
            // Swagger 2.0
            Swagger swagger = new SwaggerParser().parse(jsonSpec);
            if (swagger == null || swagger.getInfo() == null) {
                throw new IllegalArgumentException("Invalid Swagger 2.0 spec");
            }
        } else {
            throw new IllegalArgumentException(
                    "Spec is not OpenAPI 3.x (needs an `openapi` field) nor Swagger 2.0 (`swagger` field)."
            );
        }

        // 5) Re‑encode JSON for stepping into HTML
        String b64 = Base64.getEncoder()
                .encodeToString(jsonSpec.getBytes(StandardCharsets.UTF_8));

        // 6) Generate Redoc HTML
        String html = """
      <!DOCTYPE html>
      <html>
      <head>
        <meta charset="utf-8"/>
        <title>API Documentation</title>
        <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
      </head>
      <body>
        <div id="redoc-container"></div>
        <script>
          const spec = JSON.parse(atob("%s"));
          Redoc.init(spec, {}, document.getElementById("redoc-container"));
        </script>
      </body>
      </html>
      """.formatted(b64);

        return html;
    }


    @Tool(
            name        = "validate_openapi_spec_from_base64",
            description = "Validate a Base64‑encoded OpenAPI/Swagger spec (v2 or v3, JSON or YAML)"
    )
    public String validateSpecFromBase64(
            @ToolParam(description = "Base64‑encoded OpenAPI/Swagger spec content")
            String specBase64
    ) throws Exception {
        // 1) Decode and read into a JsonNode (handles JSON or YAML)
        byte[] decoded = Base64.getDecoder().decode(specBase64);
        String raw = new String(decoded, StandardCharsets.UTF_8).trim();
        JsonNode root = new YAMLMapper().readTree(raw);

        // 2) Branch by version
        if (root.has("openapi")) {
            // ── OpenAPI 3.x validation ─────────────────────────────
            // Serialize to pure JSON
            String jsonSpec = new ObjectMapper().writeValueAsString(root);

            // Enable full validation
            ParseOptions opts = new ParseOptions();

            SwaggerParseResult res = new OpenAPIV3Parser()
                    .readContents(jsonSpec, null, opts);
            if (res.getMessages() != null && !res.getMessages().isEmpty()) {
                // Return all parser messages
                return "Validation issues:\n• " +
                        String.join("\n• ", res.getMessages());
            }
        } else if (root.has("swagger")) {
            // ── Swagger 2.0 validation ─────────────────────────────
            SwaggerParser parser = new SwaggerParser();
            SwaggerDeserializationResult result = parser.readWithInfo(raw);
            if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                return "Validation issues:\n• " +
                        String.join("\n• ", result.getMessages());
            }
        } else {
            return "Error: Spec is neither OpenAPI 3.x nor Swagger 2.0 (missing `openapi` or `swagger` field).";
        }

        // 3) If we reach here, no messages = valid
        return "Spec is valid (no errors found).";
    }

    @Tool(
            name        = "zap_run_dynamic_plan",
            description = "Run a full OpenAPI scan with dynamic spec URL, context and timestamped report"
    )
    public String runDynamicPlan(
            @ToolParam(description = "Name of the ZAP context (e.g. petstore)") String contextName,
            @ToolParam(description = "Base API URL to include in the context") String baseUrl,
            @ToolParam(description = "OpenAPI spec URL (JSON or YAML)") String specUrl,
            @ToolParam(description = "Directory inside ZAP to write the report (e.g. /tmp)") String reportDir
    ) throws Exception {
        // 1) Build a timestamped reportFile name
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String reportFile = contextName + "-api-scan-" + timestamp + ".html";

        // 2) Construct the YAML plan string
        String yamlPlan = String.format("""
          env:
            parameters:
                failOnError: false
            contexts:
              - name: %s
                urls:
                  - %s

          jobs:
            - type: openapi
              parameters:
                context: %s
                apiUrl: %s
                failOnError: false

            - type: spider
              parameters:
                context: %s
                maxDepth: 5

            - type: activeScan
              parameters:
                context: %s
                policy: Default Policy
                maxScanDurationInMins: 10

            - type: report
              parameters:
                template: traditional-html
                reportFile: %s
                reportDir: %s
          """,
                contextName, baseUrl,
                contextName, specUrl,
                contextName,
                contextName,
                reportFile, reportDir
        );

        // 3) Write the plan to a timestamped file under /zap/wrk
        Path saveZapWorkPath = Path.of("/Users/dant/Downloads");
        Path planFile = Files.createTempFile(saveZapWorkPath, "zap-auto-job-", ".yaml");
        log.info("saving plan file {}", planFile);
        Files.writeString(planFile, yamlPlan, StandardCharsets.UTF_8);

        // 4) Tell ZAP to run that plan via runPlan (filePath must be visible to ZAP)
        ApiResponseElement resp = (ApiResponseElement) zap.callApi(
                "automation", "action", "runPlan",
                Map.of("filePath", "/zap/wrk/" + planFile.getFileName())
        );

        // 5) Return whatever ZAP returns (status/summary)
        return resp.getValue();
    }

    @Tool(
        name        = "zap_get_plan_progress",
        description = "Get the current progress of an Automation plan by planId"
    )
    public String getPlanProgress(
        @ToolParam(description = "The planId returned by zap_run_dynamic_plan or zap_run_plan") String planId
    ) throws Exception {
        // 1) Call the view endpoint
        ApiResponseSet resp = (ApiResponseSet) zap.callApi(
                "automation", "view", "planProgress",
                Map.of("planId", planId)
        );
        // 2) Prepare a JSON-friendly map
        Map<String, Object> progress = new LinkedHashMap<>();

        for (Map.Entry<String, ApiResponse> e : resp.getValuesMap().entrySet()) {
            String key = e.getKey();
            ApiResponse value = e.getValue();

            if (value instanceof ApiResponseElement elt) {
                // Single scalar
                progress.put(key, elt.getValue());

            } else if (value instanceof ApiResponseList list) {
                // A list of things
                List<Object> items = new ArrayList<>();
                for (ApiResponse item : list.getItems()) {
                    if (item instanceof ApiResponseElement ie) {
                        items.add(ie.getValue());
                    } else if (item instanceof ApiResponseSet is) {
                        items.add(is.getValuesMap());  // a nested map
                    } else {
                        items.add(item.toString());
                    }
                }
                progress.put(key, items);

            } else {
                // Fallback for anything else
                progress.put(key, value.toString());
            }
        }

        // 3) Serialize to JSON
        return new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(progress);
    }

    @Tool(
            name        = "zap_read_report_file",
            description = "Read the HTML report file generated by an Automation plan"
    )
    public String readReportFile(
            @ToolParam(description = "Absolute path to the generated report file in the ZAP container (e.g. /tmp/petstore-api-scan-20250423123000.html)")
            String reportFilePath
    ) throws Exception {
        Path path = Path.of(reportFilePath);
        // Will throw if the file doesn’t yet exist, so your client can retry
        return Files.readString(path, StandardCharsets.UTF_8);
    }

}
