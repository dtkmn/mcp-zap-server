# Logging Enhancement Proposals for MCP-ZAP Server

This document reviews current logging practices in the `mcp-zap-server` codebase and proposes enhancements to improve traceability, debugging, and operational understanding.

## 1. Current State Acknowledgement

The codebase currently utilizes SLF4J for logging, primarily through the Lombok `@Slf4j` annotation in service classes (e.g., `ActiveScanService`, `CoreService`). This is a good foundation.

The `application.yml` file includes a setting to control logging levels, for example:
```yaml
logging:
  level:
    mcp.server.zap.service.ZapService: DEBUG # Note: This specific class was not found; should be mcp.server.zap.service for broader coverage
```
This demonstrates an awareness of configurable logging levels. The proposal is to make logging more comprehensive and consistent.

## 2. Recommendations for Enhanced Logging

To improve traceability and debugging, the following logging enhancements are recommended:

### a. Tool Invocation Logging
*   **Entry and Exit:** Log the beginning and end of each public method, especially those annotated with `@Tool`. This helps trace the flow of execution.
*   **Input Parameters:** Log key input parameters upon method entry. Sensitive parameters (if any are introduced in the future, e.g., API keys for other services) should be masked or omitted.
*   **Results:** Log the result of the tool execution, or a summary if the result is very large or complex. For example, if returning a list of alerts, log the count of alerts found.

### b. ZAP API Interaction Logging
*   **API Calls:** Before making a call to the ZAP `ClientApi`, log the target method/endpoint and key parameters being sent.
*   **API Responses:** Log a summary of the response received from ZAP. For critical operations or when errors occur, more detailed response logging might be necessary. This is especially important if ZAP returns an unexpected structure.

### c. Decision Points and Logic Flow
*   Log the reasons for taking specific branches in conditional logic, especially when this logic depends on configuration or input parameters. This clarifies why certain actions were taken or skipped.

### d. Configuration Values
*   At service initialization (e.g., in constructors or `@PostConstruct` methods), log important configuration values that the service will use (e.g., default scan parameters, ZAP connection details). This helps verify that the service is configured as expected.

### e. Error Logging
*   **Contextual Information:** When catching exceptions, especially before re-throwing them as custom exceptions (as proposed in a separate error handling review), ensure the original exception is logged along with relevant contextual information (e.g., parameters at the time of failure).
*   **Clear Error Messages:** Ensure error log messages clearly state what operation failed and why, if known.

### f. Consistent Use of Log Levels
Adopt a consistent strategy for using log levels:
*   **`TRACE`:** Extremely fine-grained details, possibly for ZAP request/response bodies (if not too large or sensitive). Generally not enabled in production but useful for deep debugging.
*   **`DEBUG`:** Detailed information useful for developers during troubleshooting. This includes:
    *   Method entry/exit with parameters.
    *   Specific ZAP API calls and their parameters.
    *   Values of important local variables or intermediate results.
    *   Configuration values being used.
*   **`INFO`:** High-level information about the application's operation. This includes:
    *   Successful tool invocations (e.g., "Active scan started with ID X").
    *   Service initialization messages.
    *   Significant successful operations (e.g., "Report generated successfully at path Y").
*   **`WARN`:** Potentially harmful situations or non-critical errors that do not stop the current operation but might indicate future problems. Examples:
    *   Use of deprecated features or parameters.
    *   Retrying an operation.
    *   An optional configuration was not found, using default.
*   **`ERROR`:** Errors and exceptions that prevent an operation from completing successfully. This includes:
    *   Caught exceptions (before re-throwing or handling).
    *   Failures in critical operations (e.g., "Failed to connect to ZAP API").

## 3. Code Snippet Examples

Here are some illustrative examples of how logging can be enhanced within a service method, using `ActiveScanService.activeScan` as a basis:

```java
// In ActiveScanService.java

import org.slf4j.Logger; // Ensure correct Logger if not using Lombok's @Slf4j field name 'log'
import org.slf4j.LoggerFactory; // Or use Lombok's @Slf4j

// @Slf4j // Assuming Lombok is used
@Service
public class ActiveScanService {

    // If not using Lombok, declare explicitly:
    // private static final Logger log = LoggerFactory.getLogger(ActiveScanService.class);

    private final ClientApi zap;
    // Assuming these are injected or defined
    private final int defaultMaxScanDurationInMinutes;
    private final String defaultScanPolicy; // Example

    public ActiveScanService(ClientApi zap,
                             @Value("${zap.scan.active.options.maxScanDurationInMinutes:0}") int defaultMaxScanDurationInMinutes,
                             @Value("${zap.scan.active.defaultPolicy:Default Policy}") String defaultScanPolicy) {
        this.zap = zap;
        this.defaultMaxScanDurationInMinutes = defaultMaxScanDurationInMinutes;
        this.defaultScanPolicy = defaultScanPolicy;
        log.info("ActiveScanService initialized. Default max scan duration: {} minutes, Default policy: '{}'",
                 this.defaultMaxScanDurationInMinutes, this.defaultScanPolicy);
    }

    @Tool(name = "zap_active_scan", description = "Start an active scan...")
    public String activeScan(
            @ToolParam(description = "Target URL to scan") String targetUrl,
            @ToolParam(description = "Recurse into sub-paths? (true/false)") String recurse,
            @ToolParam(description = "Scan policy name (e.g. Default Policy, API Policy)") String policy,
            @ToolParam(description = "Optional: Maximum scan duration in minutes.", required = false) Integer maxDurationMinutesOverride
    ) throws McpZapServerException { // Using custom exceptions as per other proposals

        // Use a unique identifier for tracing this specific request/operation
        String operationId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.debug("[OpID: {}] Entering activeScan. Target URL: '{}', Recurse: '{}', Policy: '{}', MaxDurationOverride: {}",
                  operationId, targetUrl, recurse, policy, maxDurationMinutesOverride);

        // Parameter processing and decision logging
        boolean recurseBool = Boolean.parseBoolean(recurse); // Assuming validation handles incorrect strings
        String policyToUse = (policy != null && !policy.isEmpty()) ? policy : defaultScanPolicy;
        log.debug("[OpID: {}] Using scan policy: '{}'", operationId, policyToUse);

        int effectiveMaxDuration = (maxDurationMinutesOverride != null)
                                   ? maxDurationMinutesOverride
                                   : defaultMaxScanDurationInMinutes;
        if (maxDurationMinutesOverride != null) {
            log.debug("[OpID: {}] Max scan duration overridden by tool parameter: {} minutes.", operationId, maxDurationMinutesOverride);
        } else {
            log.debug("[OpID: {}] Using default max scan duration: {} minutes.", operationId, defaultMaxScanDurationInMinutes);
        }

        try {
            // Log ZAP API interactions
            log.debug("[OpID: {}] Setting ZAP option maxScanDurationInMins to: {}", operationId, effectiveMaxDuration);
            zap.ascan.setOptionMaxScanDurationInMins(effectiveMaxDuration);
            // ... set other options with similar logging ...

            log.info("[OpID: {}] Initiating ZAP active scan. Target: '{}', Policy: '{}', Recurse: {}",
                     operationId, targetUrl, policyToUse, recurseBool);
            ApiResponseElement scanResp = (ApiResponseElement) zap.ascan.scan(
                    targetUrl,
                    Boolean.toString(recurseBool), // ZAP API often expects string "true"/"false"
                    "false", // InScopeOnly - consider making this configurable too
                    policyToUse,
                    null,   // method
                    null    // postData
            );

            if (scanResp == null) {
                log.error("[OpID: {}] ZAP API call to ascan.scan returned null for target: {}", operationId, targetUrl);
                throw new ScanOperationException("Failed to start active scan on " + targetUrl + ": ZAP returned null response.");
            }

            String scanId = scanResp.getValue();
            log.info("[OpID: {}] Successfully started active scan. Scan ID: {}, Target URL: '{}'", operationId, scanId, targetUrl);

            // Log result summary
            String resultMessage = "Active scan started with ID: " + scanId;
            log.debug("[OpID: {}] Exiting activeScan. Result: '{}'", operationId, resultMessage);
            return resultMessage; // Or a structured POJO response

        } catch (ClientApiException e) {
            // Log with specific ZAP error code if available
            log.error("[OpID: {}] ZAP API call failed during activeScan for target '{}'. ZAP Error Code: {}, Message: {}",
                      operationId, targetUrl, e.getCode(), e.getMessage(), e);
            throw new ZapApiException("ZAP API error during active scan initiation on " + targetUrl, e);
        } catch (Exception e) { // Catch other unexpected exceptions
            log.error("[OpID: {}] Unexpected error during activeScan for target '{}': {}", operationId, targetUrl, e.getMessage(), e);
            throw new ScanOperationException("An unexpected error occurred while starting active scan on " + targetUrl, e);
        }
    }
}
```

**Key improvements in the example:**
*   **Operation ID (`OpID`):** A unique ID per invocation helps correlate all log messages related to a single tool execution, especially in concurrent environments. This can be implemented using MDC (Mapped Diagnostic Context) for a cleaner approach across methods.
*   **Parameter Logging:** Input parameters are logged at `DEBUG` level.
*   **Decision Logging:** Reasons for choosing specific values (e.g., `policyToUse`, `effectiveMaxDuration`) are logged.
*   **ZAP Call Logging:** Explicitly logs the intent and key parameters before calling ZAP.
*   **Error Logging Context:** Exceptions caught include the `OpID` and relevant parameters for better context.
*   **Clear Success Logging:** `INFO` level logs for successful initiation with key identifiers.
*   **Log Levels:** Demonstrates use of `DEBUG` for flow/parameters, `INFO` for major operations, and `ERROR` for exceptions.

## 4. General Configuration for Logging

*   **`application.yml` for `mcp.server.zap.service`:**
    It's recommended to set a general logging level for the primary service package and then adjust specific classes if needed.
    ```yaml
    logging:
      level:
        mcp.server.zap.service: INFO # Default for all services, override for specific classes if needed
        # mcp.server.zap.service.ActiveScanService: DEBUG # Example to make one service more verbose
        org.zaproxy.clientapi.core.ClientApi: WARN # To reduce verbosity from ZAP client library itself unless debugging ZAP comms
      pattern:
        console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n" # Example pattern
        # file: ... # Consider file logging for production
    ```
*   **MDC for Operation ID:** For more robust cross-method tracing of an operation, consider using SLF4J's MDC:
    ```java
    // At the beginning of a @Tool method
    MDC.put("opId", java.util.UUID.randomUUID().toString().substring(0,8));
    try {
        // ... your method logic ...
    } finally {
        MDC.remove("opId");
    }
    // And include %X{opId} in your log pattern in application.yml
    // console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %X{opId}%-5level %logger{36} - %msg%n"
    ```

By systematically applying these enhanced logging practices, the `mcp-zap-server` will become significantly easier to monitor, troubleshoot, and understand during both development and operation.I have completed the review of logging practices and drafted the enhancement proposals. The content is ready to be saved.
