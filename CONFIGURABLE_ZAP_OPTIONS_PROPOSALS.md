# Proposals for Configurable ZAP Options

This document analyzes hardcoded values in the `mcp-zap-server` services that affect ZAP's behavior. It proposes mechanisms to make these values configurable, enhancing flexibility for users and administrators.

---

## 1. ActiveScanService.java

This service contains several hardcoded options for active scans.

### Identified Hardcoded Values & Configuration Proposals:

#### a) `zap.ascan.enableAllScanners(null);`
*   **Current Behavior:** Enables all available active scanners for every scan. The `null` parameter typically means "all policy categories".
*   **Proposal:**
    *   **`application.yml`:** For a default behavior.
        ```yaml
        zap:
          scan:
            active:
              enableAllScanners: true # Default to enable all
              # Or, to be more granular if ZAP API supports it:
              # enabledScannersPolicy: "Default Policy" # Or a specific policy name
              # enabledScannersIds: "40012,40014" # Comma-separated list of scanner IDs to enable if not all
        ```
    *   **`@ToolParam`:** To allow overriding the default or specifying a particular scanner policy/set of scanner IDs for a given scan.
        ```java
        // In ActiveScanService.java activeScan method signature
        @ToolParam(description = "Optional: Specific scan policy name for active scan. Overrides default.", required = false) String scanPolicyNameOverride,
        @ToolParam(description = "Optional: Comma-separated list of scanner IDs to enable. Overrides policy.", required = false) String scannerIdsToEnable
        ```
*   **Java Code Adaptation:**
    ```java
    // In ActiveScanService.java
    @Value("${zap.scan.active.enableAllScanners:true}") // Default from properties
    private boolean defaultEnableAllScanners;

    // ... in activeScan method ...
    if (scannerIdsToEnable != null && !scannerIdsToEnable.isEmpty()) {
        zap.ascan.disableAllScanners(null); // Disable all first
        zap.ascan.enableScanners(scannerIdsToEnable); // Enable specific IDs
    } else if (scanPolicyNameOverride != null && !scanPolicyNameOverride.isEmpty()) {
        // This assumes ZAP has a way to enable scanners based on a policy name directly here.
        // More commonly, one sets the scan policy for the context/scan.
        // The existing `policy` parameter in `activeScan` already sets the scan policy.
        // So, `enableAllScanners(null)` might be related to the global policy category,
        // distinct from the per-scan policy. This needs clarification on ZAP's exact behavior.
        // If `policy` parameter already controls the scan policy, then `enableAllScanners(null)`
        // might be about ensuring all *types* of scanners applicable to that policy are active.
        // Let's assume for now it's a general switch.
        log.info("Note: Scan policy name override via scannerIdsToEnable/scanPolicyNameOverride for enable/disable specific scanners is illustrative. The existing 'policy' parameter is the primary way to set scan policy.");
    } else if (defaultEnableAllScanners) {
        zap.ascan.enableAllScanners(null);
    } else {
        zap.ascan.disableAllScanners(null); // Default is to disable if not overridden
    }
    ```
    *Self-correction:* The existing `policy` parameter in `activeScan` already addresses the scan policy. `enableAllScanners(null)` is more about ensuring all scanners *within* the selected policy are active. The main configurable aspect here would be whether to enable all or a specific subset *if not using a pre-defined policy that handles this*. A simpler boolean `zap.ascan.enableAllScanners(null)` vs `zap.ascan.disableAllScanners(null)` controlled by a property or tool param might be more direct.

#### b) `zap.ascan.setOptionMaxScanDurationInMins(0);`
*   **Current Behavior:** No duration limit (0 means unlimited).
*   **Proposal:**
    *   **`application.yml`:** For a default maximum duration.
        ```yaml
        zap:
          scan:
            active:
              options:
                maxScanDurationInMinutes: 0 # Default: 0 for unlimited
        ```
    *   **`@ToolParam`:** To allow specifying duration per scan.
        ```java
        // In ActiveScanService.java activeScan method signature
        @ToolParam(description = "Optional: Maximum scan duration in minutes. 0 for unlimited.", required = false) Integer maxDurationMinutes
        ```
*   **Java Code Adaptation:**
    ```java
    // In ActiveScanService.java
    @Value("${zap.scan.active.options.maxScanDurationInMinutes:0}")
    private int defaultMaxScanDurationInMinutes;

    // ... in activeScan method ...
    int effectiveMaxDuration = (maxDurationMinutes != null) ? maxDurationMinutes : defaultMaxScanDurationInMinutes;
    zap.ascan.setOptionMaxScanDurationInMins(effectiveMaxDuration);
    ```

#### c) `zap.ascan.setOptionHostPerScan(0);`
*   **Current Behavior:** No limit on hosts per scan (0 means unlimited, though typically an active scan targets one main host/context). This might refer to hosts discovered during scan (e.g. via spidering before scan).
*   **Proposal:**
    *   **`application.yml`:**
        ```yaml
        zap:
          scan:
            active:
              options:
                maxHostsPerScan: 0 # Default: 0 for unlimited
        ```
    *   **`@ToolParam`:**
        ```java
        // In ActiveScanService.java activeScan method signature
        @ToolParam(description = "Optional: Maximum number of hosts per scan. 0 for unlimited.", required = false) Integer maxHostsPerScan
        ```
*   **Java Code Adaptation:**
    ```java
    // In ActiveScanService.java
    @Value("${zap.scan.active.options.maxHostsPerScan:0}")
    private int defaultMaxHostsPerScan;

    // ... in activeScan method ...
    int effectiveMaxHosts = (maxHostsPerScan != null) ? maxHostsPerScan : defaultMaxHostsPerScan;
    zap.ascan.setOptionHostPerScan(effectiveMaxHosts);
    ```

#### d) `zap.ascan.setOptionThreadPerHost(10);`
*   **Current Behavior:** 10 threads per host.
*   **Proposal:**
    *   **`application.yml`:**
        ```yaml
        zap:
          scan:
            active:
              options:
                threadsPerHost: 10 # Default
        ```
    *   **`@ToolParam`:**
        ```java
        // In ActiveScanService.java activeScan method signature
        @ToolParam(description = "Optional: Number of threads per host for scanning.", required = false) Integer threadsPerHost
        ```
*   **Java Code Adaptation:**
    ```java
    // In ActiveScanService.java
    @Value("${zap.scan.active.options.threadsPerHost:10}")
    private int defaultThreadsPerHost;

    // ... in activeScan method ...
    int effectiveThreads = (threadsPerHost != null) ? threadsPerHost : defaultThreadsPerHost;
    zap.ascan.setOptionThreadPerHost(effectiveThreads);
    ```

#### e) `zap.ascan.setOptionDelayInMs(500);`
*   **Current Behavior:** 500ms delay between requests.
*   **Proposal:**
    *   **`application.yml`:**
        ```yaml
        zap:
          scan:
            active:
              options:
                delayInMilliseconds: 500 # Default
        ```
    *   **`@ToolParam`:**
        ```java
        // In ActiveScanService.java activeScan method signature
        @ToolParam(description = "Optional: Delay in milliseconds between scan requests.", required = false) Integer delayInMilliseconds
        ```
*   **Java Code Adaptation:**
    ```java
    // In ActiveScanService.java
    @Value("${zap.scan.active.options.delayInMilliseconds:500}")
    private int defaultDelayInMilliseconds;

    // ... in activeScan method ...
    int effectiveDelay = (delayInMilliseconds != null) ? delayInMilliseconds : defaultDelayInMilliseconds;
    zap.ascan.setOptionDelayInMs(effectiveDelay);
    ```

#### f) Commented out options like `zap.ascan.setOptionTimeoutInSecs(60);`
*   **Current Behavior:** These are not active.
*   **Proposal:** If these options are deemed useful, they should be made configurable similarly using `application.yml` for defaults and optional `@ToolParam` for per-call overrides.
    *   Example for `setOptionTimeoutInSecs`:
        *   **`application.yml`:**
            ```yaml
            zap:
              scan:
                active:
                  options:
                    ruleTimeoutInSeconds: 60 # Default timeout for a scanner rule
            ```
        *   **`@ToolParam`:**
            ```java
            @ToolParam(description = "Optional: Timeout in seconds for each scanner rule.", required = false) Integer ruleTimeoutInSeconds
            ```
        *   **Java Code:** Similar to other examples, using `@Value` and checking the tool parameter.

---

## 2. SpiderScanService.java

### Identified Hardcoded Values & Configuration Proposals:

#### a) `zap.core.setOptionTimeoutInSecs(60);`
*   **Current Behavior:** Sets global ZAP timeout to 60 seconds. This is a core setting, not spider-specific.
*   **Proposal:** This is a global ZAP option. It should ideally be configured once.
    *   **`application.yml`:**
        ```yaml
        zap:
          core:
            options:
              timeoutInSeconds: 60
        ```
    *   **Java Code Adaptation (in a ZAP core configuration component or on startup):**
        ```java
        // Potentially in ZapApiConfig.java or a dedicated ZAP initializer
        @Value("${zap.core.options.timeoutInSeconds:60}")
        private int coreTimeoutInSeconds;

        // Call this during initialization if ClientApi object is available
        // public void configureZapCoreOptions(ClientApi zap) {
        //     zap.core.setOptionTimeoutInSecs(coreTimeoutInSeconds);
        // }
        ```
        *Self-correction:* Setting global options like `core.setOptionTimeoutInSecs` within a specific tool call (`startSpider`) can have side effects on other concurrent or subsequent operations. Such global settings are better handled at application startup or via a dedicated ZAP configuration tool/service, rather than being tied to a spider scan tool. If it must be per-spider invocation for some reason, it should be reset afterwards, which is complex. Prefer `application.yml` and one-time setup.

#### b) `zap.spider.setOptionThreadCount(5);`
*   **Current Behavior:** Spider uses 5 threads.
*   **Proposal:**
    *   **`application.yml`:**
        ```yaml
        zap:
          scan:
            spider:
              options:
                threadCount: 5
        ```
    *   **`@ToolParam` (for `startSpider`):**
        ```java
        @ToolParam(description = "Optional: Number of threads for the spider scan.", required = false) Integer spiderThreadCount
        ```
*   **Java Code Adaptation:**
    ```java
    // In SpiderScanService.java
    @Value("${zap.scan.spider.options.threadCount:5}")
    private int defaultSpiderThreadCount;

    // ... in startSpider method ...
    int effectiveSpiderThreads = (spiderThreadCount != null) ? spiderThreadCount : defaultSpiderThreadCount;
    zap.spider.setOptionThreadCount(effectiveSpiderThreads);
    ```

#### c) `zap.spider.scan(targetUrl, "10", "true", "", "false");`
*   **Hardcoded values:**
    *   `maxDepth ("10")`: Maximum depth to crawl.
    *   `recurse ("true")`: Whether to recurse. (This is different from active scan's recurse parameter usage).
    *   `contextName ("")`: Optional context name.
    *   `subtreeOnly ("false")`: Whether to scan only within the specified subtree.
*   **Proposal:** These are prime candidates for `@ToolParam` with defaults potentially from `application.yml`.
    *   **`application.yml`:**
        ```yaml
        zap:
          scan:
            spider:
              defaults:
                maxDepth: 10
                recurse: true
                subtreeOnly: false
                # contextName is usually dynamic or empty
        ```
    *   **`@ToolParam` (for `startSpider`):**
        ```java
        @ToolParam(description = "Optional: Maximum depth for the spider to crawl.", required = false) Integer maxDepth,
        @ToolParam(description = "Optional: Whether the spider should recurse. (true/false)", required = false) String recurseSpider, // String to handle boolean parsing
        @ToolParam(description = "Optional: Name of the ZAP context to use for the spider scan.", required = false) String spiderContextName,
        @ToolParam(description = "Optional: Whether to scan only within the specified subtree of the target URL. (true/false)", required = false) String subtreeOnly
        ```
*   **Java Code Adaptation:**
    ```java
    // In SpiderScanService.java
    @Value("${zap.scan.spider.defaults.maxDepth:10}")
    private int defaultSpiderMaxDepth;
    @Value("${zap.scan.spider.defaults.recurse:true}")
    private boolean defaultSpiderRecurse;
    @Value("${zap.scan.spider.defaults.subtreeOnly:false}")
    private boolean defaultSpiderSubtreeOnly;

    // ... in startSpider method ...
    int effectiveMaxDepth = (maxDepth != null) ? maxDepth : defaultSpiderMaxDepth;
    boolean effectiveRecurse = (recurseSpider != null) ? Boolean.parseBoolean(recurseSpider) : defaultSpiderRecurse; // Add error handling for parseBoolean
    String effectiveContextName = (spiderContextName != null) ? spiderContextName : "";
    boolean effectiveSubtreeOnly = (subtreeOnly != null) ? Boolean.parseBoolean(subtreeOnly) : defaultSpiderSubtreeOnly; // Add error handling

    ApiResponse resp = zap.spider.scan(
        targetUrl,
        Integer.toString(effectiveMaxDepth),
        Boolean.toString(effectiveRecurse),
        effectiveContextName,
        Boolean.toString(effectiveSubtreeOnly)
    );
    ```

---

## 3. Other Services (OpenApiService, ReportService, CoreService)

A quick review of these services indicates fewer ZAP-behavior-altering hardcoded values:

*   **`OpenApiService`**: The `hostOverride` parameter is already a tool parameter. No other obvious ZAP options are hardcoded in the methods.
*   **`ReportService`**:
    *   `zap.reports.generate(...)` has many parameters. Some are hardcoded like `title ("My ZAP Scan Report")`, `description ("")`, `contexts ("")`, `sections ("")`, etc.
    *   **Proposal for `title` and `description`:** Could be made `@ToolParam`s.
        ```java
        // In ReportService.java getHtmlReport method signature
        @ToolParam(description = "Optional: Title for the generated report.", required = false) String reportTitle,
        @ToolParam(description = "Optional: Description for the generated report.", required = false) String reportDescription
        ```
    *   **Proposal for `reportDirectory`:** This is already injected via `@Value("${zap.report.directory:/zap/wrk}")`, so it's configurable via `application.yml`. This is good.
    *   The `reportFileNamePattern ("zap-report-" + System.currentTimeMillis())` ensures unique names. Could add a prefix from `application.yml` if desired: `zap.report.filenamePrefix: "my-custom-report-"`.
*   **`CoreService`**: Methods like `getAlerts`, `getHosts`, etc., primarily retrieve data and don't set many ZAP options. The `start` and `count` for alerts are hardcoded to `0` and `-1` (all alerts). These could be exposed as parameters if fetching subsets of alerts is a requirement.

---

## General Recommendations for Implementation

1.  **Configuration Properties Class:** For numerous properties in `application.yml` (e.g., under `zap.scan.active.options`), consider using a `@ConfigurationProperties` class in Java for type-safe binding and easier management instead of many individual `@Value` annotations.
    ```java
    // Example for Active Scan Options
    @Configuration
    @ConfigurationProperties(prefix = "zap.scan.active.options")
    @Data // Lombok
    public class ZapActiveScanOptionsConfig {
        private int maxScanDurationInMinutes = 0;
        private int maxHostsPerScan = 0;
        private int threadsPerHost = 10;
        private int delayInMilliseconds = 500;
        private int ruleTimeoutInSeconds = 60;
        // Add other options here
    }

    // Then inject this into ActiveScanService:
    // private final ZapActiveScanOptionsConfig activeScanOptionsConfig;
    // And use, e.g., activeScanOptionsConfig.getMaxScanDurationInMinutes()
    ```

2.  **Boolean Parsing for Tool Parameters:** When taking boolean flags as string `@ToolParam`s (e.g., "true"/"false"), implement robust parsing (e.g., `Boolean.parseBoolean(stringValue)` which is case-insensitive for "true") and consider clear error messages if the string is not a valid boolean representation. JSR 380 `@Pattern(regexp="^(?i)(true|false)$")` is good for validation.

3.  **Documentation:** Clearly document all new `application.yml` properties and `@ToolParam`s in the `README.md` so users know how to configure these options.

By making these ZAP options configurable, the `mcp-zap-server` becomes a much more adaptable and powerful tool for different use cases and ZAP environments.I have completed the analysis of hardcoded values and drafted proposals for making them configurable. The content is ready to be saved to the markdown file.
