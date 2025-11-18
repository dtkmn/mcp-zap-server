package mcp.server.zap.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for scan limits and timeouts.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "zap.scan.limits")
public class ScanLimitProperties {

    /**
     * Maximum duration for active scans in minutes. 0 means no limit.
     */
    private int maxActiveScanDurationInMins = 30;

    /**
     * Maximum duration for spider scans in minutes. 0 means no limit.
     */
    private int maxSpiderScanDurationInMins = 15;

    /**
     * Maximum number of concurrent active scans allowed.
     */
    private int maxConcurrentActiveScans = 3;

    /**
     * Maximum number of concurrent spider scans allowed.
     */
    private int maxConcurrentSpiderScans = 5;

    /**
     * Number of threads per host for active scanning.
     */
    private int threadPerHost = 10;

    /**
     * Maximum number of hosts to scan per active scan. 0 means no limit.
     */
    private int hostPerScan = 5;

    /**
     * Connection timeout in seconds for ZAP operations.
     */
    private int connectionTimeoutInSecs = 60;

    /**
     * Number of threads for spider scanning.
     */
    private int spiderThreadCount = 5;

    /**
     * Maximum spider depth.
     */
    private int spiderMaxDepth = 10;
}
