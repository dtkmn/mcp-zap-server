# POJO Return Type Improvement Proposals

This document proposes improvements to the return types of certain methods in `mcp-zap-server` services by replacing simple strings or lists of formatted strings with structured Plain Old Java Objects (POJOs). This enhances the usability of the tools for AI agents by providing richer, machine-parsable data.

## General POJO Design Principles
*   **Immutability:** Prefer immutable POJOs if their state doesn't change after creation.
*   **Lombok:** Use Lombok annotations like `@Data` (or `@Value` for immutable classes), `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` for conciseness and ease of use.
*   **Clarity:** Field names should be clear and correspond to the data they represent from ZAP.
*   **Nesting:** Use nested POJOs if the data structure is complex.

## Serialization to JSON
Spring Boot applications that include `spring-boot-starter-web` (which is a dependency for `spring-ai-starter-mcp-server-webmvc` when using HTTP transports like SSE) typically come with Jackson ObjectMapper pre-configured. When a `@Tool` annotated method (or any Spring MVC controller method) returns a POJO, Spring Boot automatically serializes it into a JSON response. This means no extra configuration is usually needed for this serialization.

---

## 1. CoreService.java

### Method: `getAlerts(String baseUrl)`

*   **Current Return Type:** `List<String>` (formatted strings like `"%s (risk: %s) at %s"`)
*   **Problem:** The AI agent receives a pre-formatted string. It has to parse this string to extract individual fields like alert name, risk, and URL, which is error-prone and inefficient.
*   **Proposed POJO:** `ZapAlert`

    ```java
    import lombok.Builder;
    import lombok.Data;
    // Or consider lombok.Value for immutability if appropriate after creation

    @Data
    @Builder
    public class ZapAlert {
        private String id; // ZAP alert ID if available and useful
        private String name;
        private String risk; // e.g., "High", "Medium", "Low", "Informational"
        private String confidence; // e.g., "High", "Medium", "Low", "False Positive"
        private String url;
        private String other; // Other URLs involved, if any
        private String param; // Parameter involved, if any
        private String evidence; // Evidence of the alert
        private String description;
        private String solution;
        private String reference; // Link to external documentation (e.g., CWE, WASC)
        private String cweid; // Common Weakness Enumeration ID
        private String wascid; // Web Application Security Consortium Threat Classification ID
        private String sourceid; // ZAP source ID for the alert
        // Add any other relevant fields available from ZAP's ApiResponseSet for an alert
    }
    ```
*   **Modified Method Signature:**
    ```java
    // In CoreService.java
    @Tool(name = "zap_alerts", description = "Retrieve detailed alerts for the given base URL")
    public List<ZapAlert> getAlerts(@ToolParam(description = "Base URL to filter alerts (optional)") String baseUrl) {
        // ... logic to fetch alerts ...
        List<ZapAlert> detailedAlerts = new ArrayList<>();
        for (ApiResponse item : resp.getItems()) {
            ApiResponseSet set = (ApiResponseSet) item;
            ZapAlert alert = ZapAlert.builder()
                .id(set.getStringValue("id")) // Assuming 'id' is available
                .name(set.getStringValue("alert")) // or "name"
                .risk(set.getStringValue("risk"))
                .confidence(set.getStringValue("confidence"))
                .url(set.getStringValue("url"))
                .other(set.getStringValue("other"))
                .param(set.getStringValue("param"))
                .evidence(set.getStringValue("evidence"))
                .description(set.getStringValue("description"))
                .solution(set.getStringValue("solution"))
                .reference(set.getStringValue("reference"))
                .cweid(set.getStringValue("cweid"))
                .wascid(set.getStringValue("wascid"))
                .sourceid(set.getStringValue("sourceid"))
                .build();
            detailedAlerts.add(alert);
        }
        return detailedAlerts;
    }
    ```

### Method: `getHosts()`
*   **Current Return Type:** `List<String>`
*   **Problem:** Returns a simple list of hostnames. This might be sufficient.
*   **Proposed POJO:** (Optional, could remain `List<String>` unless more host-specific data from ZAP is desired, e.g., port, SSL status if available directly from this call).
    If more data is available per host from `zap.core.hosts()`:
    ```java
    @Data
    @Builder
    public class ZapHost {
        private String hostname;
        // private int port; // If available
        // private boolean ssl; // If available
    }
    ```
*   **Consideration:** The current ZAP client API `zap.core.hosts()` seems to return a simple list of hostname strings directly. If it only returns strings, a POJO is overkill unless this method is enhanced to fetch more details per host. For now, `List<String>` is likely fine.

### Method: `getSites()`
*   **Current Return Type:** `List<String>`
*   **Problem:** Similar to `getHosts()`, returns a simple list of site URLs.
*   **Proposed POJO:** (Optional, could remain `List<String>` unless more site-specific data from ZAP is desired, e.g., SSL status, number of alerts, etc., which would require more complex ZAP queries).
    If more data is available per site from `zap.core.sites()`:
    ```java
    @Data
    @Builder
    public class ZapSite {
        private String url;
        // private boolean hasSsl; // if available
        // private int alertCount; // if available and performant to fetch
    }
    ```
*   **Consideration:** Similar to `getHosts()`, `zap.core.sites()` likely returns strings. `List<String>` is acceptable.

### Method: `getUrls(String baseUrl)`
*   **Current Return Type:** `List<String>`
*   **Problem:** Returns a simple list of URLs.
*   **Proposed POJO:** (Optional, could remain `List<String>` unless ZAP provides more metadata per URL in this specific call, e.g., HTTP methods found, content types).
    If more data is available per URL from `zap.core.urls()`:
    ```java
    @Data
    @Builder
    public class ZapUrlDetails {
        private String url;
        // private List<String> methods; // e.g., GET, POST
        // private String mediaType; // if available
    }
    ```
*   **Consideration:** `zap.core.urls()` likely returns strings. `List<String>` is acceptable.

---

## 2. ActiveScanService.java

### Method: `activeScan(...)`
*   **Current Return Type:** `String` (e.g., "Active scan started with ID: " + scanId)
*   **Problem:** Returns a human-readable string. The scan ID is the critical piece of information.
*   **Proposed POJO:** `ScanInitiationResponse`
    ```java
    @Data
    @Builder
    public class ScanInitiationResponse {
        private String scanId;
        private String message; // e.g., "Active scan successfully initiated."
        private String targetUrl;
    }
    ```
*   **Modified Method Signature & Return:**
    ```java
    // In ActiveScanService.java
    public ScanInitiationResponse activeScan(...) throws Exception {
        // ...
        String scanId = scanResp.getValue();
        log.info("Started active scan with ID {} on {}", scanId, targetUrl);
        return ScanInitiationResponse.builder()
            .scanId(scanId)
            .message("Active scan successfully initiated.")
            .targetUrl(targetUrl)
            .build();
    }
    ```

### Method: `getActiveScanStatus(...)`
*   **Current Return Type:** `String` (e.g., "Active Scan [" + scanId + "] is " + pct + "% complete")
*   **Problem:** Human-readable string. Agent needs structured progress.
*   **Proposed POJO:** `ScanStatusResponse`
    ```java
    @Data
    @Builder
    public class ScanStatusResponse {
        private String scanId;
        private int progressPercentage; // Integer 0-100
        private String statusMessage; // e.g., "Running", "Completed", "Failed" (if ZAP provides this)
    }
    ```
*   **Modified Method Signature & Return:**
    ```java
    // In ActiveScanService.java
    public ScanStatusResponse getActiveScanStatus(...) throws Exception {
        // ...
        String pct = ((ApiResponseElement) resp).getValue();
        return ScanStatusResponse.builder()
            .scanId(scanId)
            .progressPercentage(Integer.parseInt(pct)) // Add error handling for parseInt
            // .statusMessage(determineStatusMessage(pct)) // ZAP only gives percentage here
            .build();
    }
    ```

### Method: `stopActiveScan(...)` and `stopAllScans()`
*   **Current Return Type:** `String` (confirmation message)
*   **Problem:** Human-readable string.
*   **Proposed POJO:** `OperationStatusResponse` (a generic response for actions)
    ```java
    @Data
    @Builder
    public class OperationStatusResponse {
        private boolean success;
        private String message;
        private String operation; // e.g., "stop_active_scan"
        private String targetId; // e.g., scanId, if applicable
    }
    ```
*   **Modified Method Signature & Return (example for `stopActiveScan`):**
    ```java
    // In ActiveScanService.java
    public OperationStatusResponse stopActiveScan(...) throws Exception {
        zap.ascan.stop(scanId);
        return OperationStatusResponse.builder()
            .success(true)
            .message("Active scan stopped successfully.")
            .operation("stop_active_scan")
            .targetId(scanId)
            .build();
    }
    // Similar for stopAllScans, targetId could be null or "ALL"
    ```

---

## 3. OpenApiService.java

### Method: `importOpenApiSpec(...)` and `importOpenApiSpecFile(...)`
*   **Current Return Type:** `String` (e.g., "Import completed asynchronously (jobs: ...)" or "Import completed synchronously...")
*   **Problem:** Human-readable string. Structure would be better.
*   **Proposed POJO:** `OpenApiImportResponse`
    ```java
    import java.util.List;
    import lombok.Builder;
    import lombok.Data;

    @Data
    @Builder
    public class OpenApiImportResponse {
        private String message; // Human-readable summary
        private boolean asynchronous; // True if background jobs were started
        private List<String> jobIds; // List of ZAP job IDs if asynchronous
        private String importSource; // "URL" or "FILE"
        private String sourceLocation; // The URL or file path provided
    }
    ```
*   **Modified Method Signature & Return (example for `importOpenApiSpec`):**
    ```java
    // In OpenApiService.java
    public OpenApiImportResponse importOpenApiSpec(...) throws Exception {
        // ...
        return OpenApiImportResponse.builder()
            .message(importIds.isEmpty() ? "Import completed synchronously and is ready to scan." : "Import completed asynchronously and is ready to scan.")
            .asynchronous(!importIds.isEmpty())
            .jobIds(importIds)
            .importSource("URL")
            .sourceLocation(apiUrl)
            .build();
    }
    ```

---

## 4. ReportService.java

### Method: `viewTemplates()`
*   **Current Return Type:** `String` (newline-separated list of template names)
*   **Problem:** While simple, a list of objects could provide more details if ZAP offers them (e.g., description, type).
*   **Proposed POJO:** `ReportTemplateInfo`
    ```java
    @Data
    @Builder
    public class ReportTemplateInfo {
        private String id; // The template ID/name used in generation
        // private String name; // A more descriptive name, if available
        // private String description; // If ZAP provides this
        // private String type; // e.g., HTML, JSON, XML, if available
    }
    ```
*   **Modified Method Signature & Return:**
    ```java
    // In ReportService.java
    public List<ReportTemplateInfo> viewTemplates() {
        // ...
        // Assuming ApiResponseList contains ApiResponseSet items for each template
        // This part is speculative based on ZAP API structure for templates
        List<ReportTemplateInfo> templates = new ArrayList<>();
        for (ApiResponse item : list.getItems()) {
            if (item instanceof ApiResponseElement) { // If it's just a list of strings
                 templates.add(ReportTemplateInfo.builder().id(item.toString()).build());
            } else if (item instanceof ApiResponseSet) { // If it's structured
                 ApiResponseSet set = (ApiResponseSet) item;
                 templates.add(ReportTemplateInfo.builder()
                    .id(set.getStringValue("id")) // or "template" or "name"
                    // .name(set.getStringValue("displayName"))
                    // .description(set.getStringValue("description"))
                    .build());
            }
        }
        return templates;
        // If ZAP truly only returns a list of strings, then List<String> is fine.
        // The current code `sb.append(item.toString()).append("\n");` suggests it might be simple strings.
    }
    ```
    *Self-correction:* The current implementation `sb.append(item.toString()).append("\n");` suggests `zap.reports.templates()` likely returns a list where each item's `toString()` is its name. If ZAP only provides template names as strings, `List<String>` remains acceptable. A POJO is only useful if ZAP provides more structured data for each template.

### Method: `getHtmlReport(...)`
*   **Current Return Type:** `String` (path to the generated report file)
*   **Problem:** Returns just a file path. Could be more informative.
*   **Proposed POJO:** `ReportGenerationResponse`
    ```java
    @Data
    @Builder
    public class ReportGenerationResponse {
        private String reportFilePath; // Absolute path in the container, or relative to a known volume
        private String reportTitle;
        private String templateUsed;
        private String themeUsed;
        private String sitesIncluded; // Comma-separated string as passed
        private String message; // e.g., "Report generated successfully."
    }
    ```
*   **Modified Method Signature & Return:**
    ```java
    // In ReportService.java
    public ReportGenerationResponse getHtmlReport(...) {
        // ...
        String fileName = ((ApiResponseElement) raw).getValue();
        Path reportPath = Paths.get(fileName); // This path might be inside the container
        return ReportGenerationResponse.builder()
            .reportFilePath(reportPath.toString())
            .reportTitle("My ZAP Scan Report") // The title passed to ZAP
            .templateUsed(reportTemplate)
            .themeUsed(theme)
            .sitesIncluded(sites)
            .message("Report generated successfully at: " + reportPath.toString())
            .build();
    }
    ```

---

## 5. SpiderScanService.java

### Method: `startSpider(...)`
*   **Current Return Type:** `String` (e.g., "Spider scan started with ID: " + scanId)
*   **Problem:** Similar to `activeScan`, returns a human-readable string.
*   **Proposed POJO:** `ScanInitiationResponse` (can reuse the one from `ActiveScanService`)
*   **Modified Method Signature & Return:**
    ```java
    // In SpiderScanService.java
    public ScanInitiationResponse startSpider(...) {
        // ...
        String scanId = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
        return ScanInitiationResponse.builder()
            .scanId(scanId)
            .message("Spider scan successfully initiated.")
            .targetUrl(targetUrl) // Add if you want to echo back the target
            .build();
    }
    ```

### Method: `getSpiderStatus(...)`
*   **Current Return Type:** `String` (percentage string)
*   **Problem:** Just a number as a string.
*   **Proposed POJO:** `ScanStatusResponse` (can reuse the one from `ActiveScanService`)
*   **Modified Method Signature & Return:**
    ```java
    // In SpiderScanService.java
    public ScanStatusResponse getSpiderStatus(...) {
        // ...
        String progress = ((org.zaproxy.clientapi.core.ApiResponseElement) resp).getValue();
        return ScanStatusResponse.builder()
            .scanId(scanId)
            .progressPercentage(Integer.parseInt(progress)) // Add error handling for parseInt
            // .statusMessage(determineStatusMessage(progress)) // ZAP only gives percentage here
            .build();
    }
    ```

---

By adopting these POJO-based return types, the `mcp-zap-server` tools will provide more structured, machine-readable data to AI agents, making it easier for them to consume and act upon the information provided by ZAP.I have completed the analysis and proposals for POJO return types. I will now save this information into the specified markdown file.
