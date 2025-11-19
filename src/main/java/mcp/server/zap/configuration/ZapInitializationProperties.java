package mcp.server.zap.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for ZAP initialization settings.
 * These settings are applied programmatically at startup via the Network API.
 */
@Data
@Component
@ConfigurationProperties(prefix = "zap.initialization")
public class ZapInitializationProperties {
    
    /**
     * User-Agent string to use for HTTP requests.
     * Default simulates Chrome browser to bypass basic bot detection.
     */
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    
    /**
     * Connection timeout in seconds for HTTP requests.
     * Higher values prevent premature timeouts when scanning slow sites.
     */
    private int connectionTimeoutInSecs = 300;
    
    /**
     * DNS Time-To-Live for successful queries in seconds.
     * Simulates real browser DNS caching behavior.
     */
    private int dnsTtlSuccessfulQueries = 60;
}
