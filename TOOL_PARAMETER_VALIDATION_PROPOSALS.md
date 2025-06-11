# Tool Parameter Validation Proposals

This document outlines suggested validation rules for input parameters of methods annotated with `@Tool` in the `mcp-zap-server` services.

**Recommended Validation Mechanism:**
For all services, it's recommended to:
1.  Add `@Validated` at the class level (e.g., `@Service @Validated @Slf4j`).
2.  Use JSR 380 (Jakarta Bean Validation) annotations directly on the parameters of the `@Tool` annotated methods (e.g., `@NotNull`, `@NotBlank`, `@URL`, `@Pattern`, `@Min`). Spring automatically picks these up when `@Validated` is present on the class.
3.  For more complex validation (e.g., conditional validation, cross-parameter validation, or custom parsing like for "true"/"false" strings to boolean), a custom validator or manual checks at the beginning of the method might be necessary. Spring AI's current support for JSR 380 annotations on `@ToolParam` might need verification; if not directly supported, manual validation within the method body using a Validator instance or utility methods would be the alternative.

---

## 1. ActiveScanService.java

*(Content previously read)*

### Method: `activeScan`
  - **Parameter Name:** `targetUrl`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Target URL to scan"
    - **Suggested Validation Rules:**
      - Not null or empty.
      - Must be a valid URL format.
      - Scheme should be http or https.
    - **Recommended Validation Mechanism:** `@NotBlank @URL(protocol = "http", host = "", port = -1, regexp = "^(http|https).*")` (Note: `@URL` might need adjustment for flexibility or use a custom `@Pattern` for URL structure if `@URL` is too restrictive, especially regarding host/port which might be internal). A general URL pattern like `@Pattern(regexp = "^(http|https)://.*")` could be more robust.

  - **Parameter Name:** `recurse`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Recurse into sub-paths? (true/false)"
    - **Suggested Validation Rules:**
      - Not null or empty.
      - Must be a string "true" or "false" (case-insensitive).
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^(?i)(true|false)$", message = "Parameter 'recurse' must be 'true' or 'false'")`. (Inside the method, parse to boolean).

  - **Parameter Name:** `policy`
    - **Parameter Type:** `String`
    * **Current Usage/Description:** "Scan policy name (e.g. Default Policy, API Policy)"
    * **Suggested Validation Rules:**
        * Can be null or empty (if ZAP defaults it or if an empty string implies default). If a policy is always required, then `@NotBlank`. ZAP likely has a default, so allowing empty might be fine, but this should be clarified. Assuming it can be optional or ZAP has a default.
        * If provided, should not consist only of whitespace (if not allowed by ZAP).
    * **Recommended Validation Mechanism:** `@Pattern(regexp = "^$|^[\\w\\s-]+$", message = "Policy name can be empty or contain alphanumeric characters, spaces, and hyphens")` if specific format is known or just allow any non-blank if ZAP handles various names. If it's truly optional and empty means default, no specific annotation might be needed beyond what ZAP handles, or a simple check for excessive length if that's a concern.

### Method: `getActiveScanStatus`
  - **Parameter Name:** `scanId`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "The scan ID returned when you started the Active Scan"
    - **Suggested Validation Rules:**
      - Not null or empty.
      - Should be a numeric string (ZAP scan IDs are typically integers).
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^[0-9]+$", message = "Scan ID must be a numeric string")`

### Method: `stopActiveScan`
  - **Parameter Name:** `scanId`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "The scanId returned by zap_active_scan"
    - **Suggested Validation Rules:**
      - Not null or empty.
      - Should be a numeric string.
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^[0-9]+$", message = "Scan ID must be a numeric string")`

---

## 2. CoreService.java

*(Content previously read)*

### Method: `getAlerts`
  - **Parameter Name:** `baseUrl`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "baseUrl" (implicitly, base URL to filter alerts, optional)
    - **Suggested Validation Rules:**
      - Can be null or empty (as it's optional).
      - If provided, must be a valid URL format.
    - **Recommended Validation Mechanism:** `@URL(message = "If provided, baseUrl must be a valid URL")` (if parameter is not null/empty). Or a custom validator if it needs to be nullable but valid if present. A simple `@Pattern(regexp = "^(http|https)://.*|^$", message = "Base URL must be a valid HTTP/HTTPS URL or empty")` could work if empty is allowed.

### Method: `getUrls`
  - **Parameter Name:** `baseUrl`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Base URL to filter (optional)"
    - **Suggested Validation Rules:**
      - Can be null or empty.
      - If provided, must be a valid URL format.
    - **Recommended Validation Mechanism:** Similar to `getAlerts.baseUrl`: `@Pattern(regexp = "^(http|https)://.*|^$", message = "Base URL must be a valid HTTP/HTTPS URL or empty")`.

---

## 3. OpenApiService.java

### Method: `importOpenApiSpec`
  - **Parameter Name:** `apiUrl`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "OpenAPI/Swagger spec URL (JSON or YAML)"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Must be a valid absolute URL.
      - (Already has manual `new URL(apiUrl)` validation, which is good).
    - **Recommended Validation Mechanism:** `@NotBlank @URL(message = "API URL must be a valid absolute URL")`. The existing manual check is also effective but annotations make intent clearer.

  - **Parameter Name:** `hostOverride`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Host override for the API spec"
    - **Suggested Validation Rules:**
      - Can be null or empty (ZAP handles empty as no override).
      - If provided, should be a valid hostname or IP address (without scheme or port, typically). ZAP's behavior for this parameter should be checked.
    - **Recommended Validation Mechanism:** `@Pattern(regexp = "^$|^[a-zA-Z0-9.-]+$", message = "Host override must be a valid hostname or IP address, or empty")`.

### Method: `importOpenApiSpecFile`
  - **Parameter Name:** `filePath`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Path to the OpenAPI/Swagger spec file (JSON or YAML)"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Should be a valid file path string (syntactically). Actual existence/accessibility is handled by ZAP/runtime.
      - Should not contain characters invalid for file paths (e.g., null byte).
      - Consider path traversal risks if this path is constructed from less trusted input further up, though here it's directly from the AI.
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^[^\\0]+$", message = "File path cannot contain null characters")`. Further validation for path safety might involve ensuring it's within an allowed base directory, but that's usually a business logic check.

  - **Parameter Name:** `hostOverride`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "Host override for the API spec"
    - **Suggested Validation Rules:**
      - Can be null or empty.
      - If provided, should be a valid hostname or IP address.
    - **Recommended Validation Mechanism:** `@Pattern(regexp = "^$|^[a-zA-Z0-9.-]+$", message = "Host override must be a valid hostname or IP address, or empty")`.

---

## 4. ReportService.java

### Method: `getHtmlReport`
  - **Parameter Name:** `reportTemplate`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "The report template to use (eg. modern/traditional-html-plus/traditional-json-plus)"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Should match one of the known ZAP report template IDs (e.g., "traditional-html", "modern", "traditional-json-plus"). A specific list might be too restrictive if ZAP adds more.
      - Should not contain path traversal characters if this value is used in file paths (though ZAP likely handles this).
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)?$", message = "Report template name should be a valid identifier (e.g., 'traditional-html' or 'category/template-name')")`. An enum or a dynamic check against `zap.reports.templates()` could be more robust if possible.

  - **Parameter Name:** `theme`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "The report theme (dark/light)"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Must be one of "dark" or "light" (case-insensitive).
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^(?i)(dark|light)$", message = "Theme must be 'dark' or 'light'")`.

  - **Parameter Name:** `sites`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "The sites to include in the report (commas separated)"
    - **Suggested Validation Rules:**
      - Can be null or empty (if ZAP allows reporting on all sites by default when empty).
      - If provided, should be a comma-separated list of valid URLs or site identifiers recognized by ZAP. Each part should be a valid URL.
    - **Recommended Validation Mechanism:** This is tricky with annotations. A `@Pattern` could check the overall structure (e.g., `^$|^(https?://[^,]+(?:,https?://[^,]+)*)$`), but validating each URL individually might require a custom validator or manual parsing and validation within the method. For simplicity: `@Pattern(regexp = "^$|^([^,]+(?:,[^,]+)*)$", message = "Sites must be a comma-separated list; individual site validation occurs within the service.")`. Then manually split and validate each URL's format if strictness is needed.

---

## 5. SpiderScanService.java

### Method: `startSpider`
  - **Parameter Name:** `targetUrl`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "targetUrl"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Must be a valid absolute URL.
      - (Already has manual `new URL(targetUrl)` validation, which throws `IllegalArgumentException` on failure).
    - **Recommended Validation Mechanism:** `@NotBlank @URL(message = "Target URL must be a valid absolute URL")`. The existing manual check is also good.

### Method: `getSpiderStatus`
  - **Parameter Name:** `scanId`
    - **Parameter Type:** `String`
    - **Current Usage/Description:** "scanId"
    - **Suggested Validation Rules:**
      - Not null or blank.
      - Should be a numeric string (ZAP scan IDs are typically integers).
    - **Recommended Validation Mechanism:** `@NotBlank @Pattern(regexp = "^[0-9]+$", message = "Scan ID must be a numeric string")`.
---
