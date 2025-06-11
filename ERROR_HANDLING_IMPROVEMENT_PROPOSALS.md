# Error Handling Improvement Proposals for MCP-ZAP Server

This document outlines an analysis of current error handling practices in the `mcp-zap-server` services and provides recommendations for improvement by using specific custom exceptions and structured error responses.

## Base Custom Exception Proposal

It's beneficial to define a base custom exception for the application:

```java
// Base Exception
public class McpZapServerException extends RuntimeException {
    public McpZapServerException(String message) {
        super(message);
    }

    public McpZapServerException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Specific Custom Exceptions
public class ZapApiException extends McpZapServerException {
    public ZapApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class McpToolParameterException extends McpZapServerException {
    public McpToolParameterException(String message) {
        super(message);
    }
     public McpToolParameterException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class ScanOperationException extends McpZapServerException {
    public ScanOperationException(String message, Throwable cause) {
        super(message, cause);
    }
     public ScanOperationException(String message) {
        super(message);
    }
}

public class ReportGenerationException extends McpZapServerException {
    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class InvalidUrlException extends McpToolParameterException {
    public InvalidUrlException(String message, Throwable cause) {
        super(message, cause);
    }
     public InvalidUrlException(String message) {
        super(message);
    }
}
```
*Note: For parameters validated using JSR 380 annotations, Spring's `MethodArgumentNotValidException` (if using `@RequestBody` in typical MVC) or `ConstraintViolationException` (for general bean/method validation) would typically be thrown. These could be handled globally and translated to `McpToolParameterException` or a specific error response.*

---

## Analysis of Service Classes

### 1. ActiveScanService.java

*   **Methods Declaring `throws Exception`:**
    *   `activeScan(String targetUrl, String recurse, String policy)`
    *   `getActiveScanStatus(String scanId)`
    *   `stopActiveScan(String scanId)`

*   **Proposed Specific Custom Exceptions:**
    *   **`activeScan`:**
        *   `McpToolParameterException` (or `InvalidUrlException`): If `targetUrl` is invalid (though current proposal is to use validation annotations).
        *   `McpToolParameterException`: If `recurse` is not "true" or "false".
        *   `ZapApiException`: For any underlying errors from `zap.ascan.scan(...)` or other ZAP client calls.
        *   `ScanOperationException`: If the call to ZAP is successful but ZAP itself fails to initiate the scan for a known reason (e.g., invalid policy name not caught by basic validation).
    *   **`getActiveScanStatus`:**
        *   `McpToolParameterException`: If `scanId` is invalid.
        *   `ZapApiException`: For underlying ZAP client errors.
        *   `ScanOperationException`: If ZAP reports an issue with retrieving status for a valid-looking scanId (e.g., scan not found).
    *   **`stopActiveScan`:**
        *   `McpToolParameterException`: If `scanId` is invalid.
        *   `ZapApiException`: For underlying ZAP client errors.
        *   `ScanOperationException`: If ZAP reports an issue with stopping the scan.
*   **Method `stopAllScans()`:**
    *   Currently catches `Exception` and returns an error message string.
    *   **Recommendation:** Should throw `ScanOperationException` or `ZapApiException` instead of returning a string. The global handler would then format the error.

### 2. CoreService.java

*   **Methods Declaring `throws Exception`:**
    *   `getAlerts(String baseUrl)`
    *   `getHosts()`
    *   `getSites()`
    *   `getUrls(String baseUrl)`

*   **Proposed Specific Custom Exceptions:**
    *   **`getAlerts`:**
        *   `McpToolParameterException` (or `InvalidUrlException`): If `baseUrl` is provided but invalid.
        *   `ZapApiException`: For any ZAP client errors during `zap.core.alerts(...)`.
    *   **`getHosts`:**
        *   `ZapApiException`: For ZAP client errors.
    *   **`sites`:**
        *   `ZapApiException`: For ZAP client errors.
    *   **`getUrls`:**
        *   `McpToolParameterException` (or `InvalidUrlException`): If `baseUrl` is provided but invalid.
        *   `ZapApiException`: For ZAP client errors.

### 3. OpenApiService.java

*   **Methods Declaring `throws Exception` (or having broad try-catch):**
    *   `importOpenApiSpec(String apiUrl, String hostOverride)`: Declares `throws Exception`.
    *   `importOpenApiSpecFile(String filePath, String hostOverride)`: Catches generic `Exception`.

*   **Proposed Specific Custom Exceptions:**
    *   **`importOpenApiSpec`:**
        *   `InvalidUrlException`: For malformed `apiUrl` (already partially handled by `MalformedURLException` catch, which should be wrapped).
        *   `McpToolParameterException`: If `hostOverride` has an invalid format (if rules are defined).
        *   `ZapApiException`: For ZAP client errors during `zap.openapi.importUrl(...)`.
    *   **`importOpenApiSpecFile`:**
        *   `McpToolParameterException`: If `filePath` is invalid (e.g., contains null bytes) or `hostOverride` format is wrong.
        *   `ZapApiException`: For ZAP client errors during `zap.openapi.importFile(...)`.
        *   `McpZapServerException`: For other file-related issues not directly from ZAP API (though ZAP client might already convert these).

### 4. ReportService.java

*   **Methods Declaring `throws Exception` (or having broad try-catch):**
    *   `viewTemplates()`: Catches generic `Exception`.
    *   `getHtmlReport(String reportTemplate, String theme, String sites)`: Catches generic `Exception`.

*   **Proposed Specific Custom Exceptions:**
    *   **`viewTemplates`:**
        *   `ZapApiException`: For ZAP client errors.
        *   `ReportGenerationException`: If the response from ZAP is not in the expected format.
    *   **`getHtmlReport`:**
        *   `McpToolParameterException`: For invalid `reportTemplate`, `theme`, or `sites` format.
        *   `ZapApiException`: For ZAP client errors.
        *   `ReportGenerationException`: If report generation fails for reasons other than direct ZAP API call issues (e.g., file system issues if ZAP client doesn't abstract this, or unexpected ZAP response).

### 5. SpiderScanService.java

*   **Methods Declaring `throws Exception` (or having broad try-catch):**
    *   `startSpider(String targetUrl)`: Catches generic `Exception` after an initial `IllegalArgumentException` for URL format.
    *   `getSpiderStatus(String scanId)`: Catches generic `Exception`.

*   **Proposed Specific Custom Exceptions:**
    *   **`startSpider`:**
        *   `InvalidUrlException`: For malformed `targetUrl` (current `IllegalArgumentException` should be replaced or wrapped by this).
        *   `ZapApiException`: For underlying ZAP client errors.
        *   `ScanOperationException`: If ZAP fails to start the spider scan.
    *   **`getSpiderStatus`:**
        *   `McpToolParameterException`: If `scanId` is invalid.
        *   `ZapApiException`: For ZAP client errors.
        *   `ScanOperationException`: If ZAP reports an issue retrieving status.

---

## Recommended Structured Error Responses and Handling

When a tool execution results in an error (i.e., one of the proposed custom exceptions is thrown), the MCP server should ideally return a structured error response to the AI agent. This allows the agent to potentially understand and react to the error.

### 1. Global Exception Handling in Spring Boot

Spring Boot applications can use `@ControllerAdvice` classes with `@ExceptionHandler` methods to centralize exception handling logic. This is commonly used in web applications to transform exceptions into HTTP responses. For an MCP server (especially one not necessarily HTTP based for all interactions, though this one uses Spring WebMVC components), a similar global mechanism is needed.

The Spring AI framework's MCP server implementation should provide hooks for this or handle it by default. If using `spring-ai-starter-mcp-server-webmvc`, `@ControllerAdvice` is the standard Spring MVC way.

```java
@ControllerAdvice
public class McpGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(McpGlobalExceptionHandler.class);

    @ExceptionHandler(McpZapServerException.class)
    public ResponseEntity<ErrorResponse> handleMcpZapServerException(McpZapServerException ex) {
        log.error("MCP ZAP Server exception: {}", ex.getMessage(), ex);
        ErrorResponse errorResponse = new ErrorResponse(getErrorCode(ex), ex.getMessage(), ex.getCause() != null ? ex.getCause().getMessage() : null);
        // Determine appropriate HTTP status - often 400 for client errors, 500 for server errors
        HttpStatus status = (ex instanceof McpToolParameterException) ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(errorResponse, status);
    }

    @ExceptionHandler(ClientApiException.class) // From ZAP Client
    public ResponseEntity<ErrorResponse> handleZapClientApiException(ClientApiException ex) {
        log.error("ZAP Client API exception: {}", ex.getMessage(), ex);
        ErrorResponse errorResponse = new ErrorResponse("ZAP_CLIENT_API_ERROR", ex.getMessage(), ex.toString()); // Or more specific details
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ConstraintViolationException.class) // From JSR 380 validation
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        String details = ex.getConstraintViolations().stream()
                           .map(cv -> cv.getPropertyPath() + " " + cv.getMessage())
                           .collect(Collectors.joining(", "));
        ErrorResponse errorResponse = new ErrorResponse("INVALID_TOOL_PARAMETER", "Validation failed for tool parameters.", details);
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class) // Fallback for any other unhandled exceptions
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled generic exception: {}", ex.getMessage(), ex);
        ErrorResponse errorResponse = new ErrorResponse("INTERNAL_SERVER_ERROR", "An unexpected error occurred.", ex.getMessage());
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String getErrorCode(McpZapServerException ex) {
        if (ex instanceof InvalidUrlException) return "INVALID_URL";
        if (ex instanceof McpToolParameterException) return "INVALID_TOOL_PARAMETER";
        if (ex instanceof ZapApiException) return "ZAP_API_ERROR"; // If wrapping a ZAP client exception
        if (ex instanceof ScanOperationException) return "SCAN_OPERATION_FAILED";
        if (ex instanceof ReportGenerationException) return "REPORT_GENERATION_FAILED";
        return "MCP_SERVER_ERROR";
    }
}

// Simple ErrorResponse DTO
record ErrorResponse(String error_code, String message, String details) {}
```

### 2. Spring AI MCP Server Error Handling

The Spring AI framework's MCP server part (`spring-ai-starter-mcp-server-webmvc`) should ideally define how tool execution exceptions are reported to the MCP client.
If it doesn't automatically convert exceptions to a specific MCP error format, the `@ControllerAdvice` approach will ensure that HTTP-based MCP interactions (like SSE) receive a JSON error. For STDIO-based interactions, the framework would need to catch exceptions from tool invocations and serialize them to an error structure on stdout.

The key is that the AI agent invoking the tool should receive a clear, structured error, not just a generic failure message or, worse, the raw exception stack trace.

### 3. Suggested JSON Error Structure

A consistent JSON structure for errors is crucial:

```json
{
  "error_code": "ERROR_CODE_STRING",
  "message": "A human-readable description of the error.",
  "details": "Optional, more specific details about the error, potentially including parameter names or specific ZAP messages."
}
```

**Example Error Codes:**
*   `INVALID_TOOL_PARAMETER`: For issues with inputs provided to a tool.
*   `ZAP_API_UNREACHABLE`: ZAP instance not accessible.
*   `ZAP_API_ERROR`: Generic error from ZAP API.
*   `ZAP_SCAN_FAILED_TO_START`: Specific to scan initiation.
*   `ZAP_REPORT_GENERATION_FAILED`: Specific to reporting.
*   `AUTHENTICATION_ERROR`: If MCP server security is added and fails.
*   `INTERNAL_SERVER_ERROR`: For unexpected issues in `mcp-zap-server`.

### Summary of Recommendations:

1.  **Define and Use Specific Custom Exceptions:** Replace generic `throws Exception` and broad `catch (Exception e)` blocks with the proposed custom exceptions (e.g., `ZapApiException`, `McpToolParameterException`, `ScanOperationException`, `ReportGenerationException`).
2.  **Wrap or Replace ZAP Client Exceptions:** Catch exceptions from the ZAP Java client API (e.g., `ClientApiException`) and wrap them in `ZapApiException` or a more specific custom exception to provide context.
3.  **Implement Global Exception Handling:** Use Spring Boot's `@ControllerAdvice` and `@ExceptionHandler` mechanisms to catch these custom exceptions (and any others like `ConstraintViolationException`).
4.  **Standardize Error Responses:** Ensure the global exception handlers convert exceptions into a standardized JSON error structure (as suggested above) that is sent back to the MCP client. This allows the AI agent to potentially parse and understand the error.
5.  **Review Spring AI MCP Server Behavior:** Investigate how the Spring AI MCP server framework handles exceptions thrown by tools by default and ensure the global exception handling integrates correctly or complements it. The goal is a consistent error reporting mechanism regardless of the transport (HTTP/SSE or STDIO).
6.  **Avoid Returning Error Messages as Success Strings:** Modify methods like `ActiveScanService.stopAllScans()` that currently return error descriptions as regular strings. They should throw exceptions instead.

By implementing these changes, the `mcp-zap-server` will become more robust, provide clearer feedback on errors, and be easier to debug and maintain.## Phase 2: Report Generation

I have analyzed the error handling practices in the provided service classes. I will now compile the findings and recommendations into the requested markdown format.
