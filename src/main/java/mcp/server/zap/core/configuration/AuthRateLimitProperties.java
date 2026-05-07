package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Dedicated throttling for public authentication exchange endpoints.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth.rate-limit")
public class AuthRateLimitProperties {
    private boolean enabled = true;
    private int capacity = 20;
    private int refillTokens = 20;
    private long refillPeriodSeconds = 60L;
    private int maxTrackedKeys = 10000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(int refillTokens) {
        this.refillTokens = refillTokens;
    }

    public long getRefillPeriodSeconds() {
        return refillPeriodSeconds;
    }

    public void setRefillPeriodSeconds(long refillPeriodSeconds) {
        this.refillPeriodSeconds = refillPeriodSeconds;
    }

    public int getMaxTrackedKeys() {
        return maxTrackedKeys;
    }

    public void setMaxTrackedKeys(int maxTrackedKeys) {
        this.maxTrackedKeys = maxTrackedKeys;
    }
}
