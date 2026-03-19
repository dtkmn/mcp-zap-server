package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for OSS-safe rate limiting, workspace quotas, and overload shedding.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.protection")
public class AbuseProtectionProperties {
    private boolean enabled = true;
    private long retryAfterSeconds = 30L;
    private long operationStaleAfterSeconds = 21600L;
    private final RateLimit rateLimit = new RateLimit();
    private final WorkspaceQuota workspaceQuota = new WorkspaceQuota();
    private final Backpressure backpressure = new Backpressure();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getOperationStaleAfterSeconds() {
        return operationStaleAfterSeconds;
    }

    public void setOperationStaleAfterSeconds(long operationStaleAfterSeconds) {
        this.operationStaleAfterSeconds = operationStaleAfterSeconds;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public WorkspaceQuota getWorkspaceQuota() {
        return workspaceQuota;
    }

    public Backpressure getBackpressure() {
        return backpressure;
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int capacity = 60;
        private int refillTokens = 60;
        private long refillPeriodSeconds = 60L;
        private int maxTrackedClients = 10000;

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

        public int getMaxTrackedClients() {
            return maxTrackedClients;
        }

        public void setMaxTrackedClients(int maxTrackedClients) {
            this.maxTrackedClients = maxTrackedClients;
        }
    }

    public static class WorkspaceQuota {
        private boolean enabled = true;
        private int maxQueuedOrRunningScanJobs = 5;
        private int maxDirectScans = 2;
        private int maxAutomationPlans = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxQueuedOrRunningScanJobs() {
            return maxQueuedOrRunningScanJobs;
        }

        public void setMaxQueuedOrRunningScanJobs(int maxQueuedOrRunningScanJobs) {
            this.maxQueuedOrRunningScanJobs = maxQueuedOrRunningScanJobs;
        }

        public int getMaxDirectScans() {
            return maxDirectScans;
        }

        public void setMaxDirectScans(int maxDirectScans) {
            this.maxDirectScans = maxDirectScans;
        }

        public int getMaxAutomationPlans() {
            return maxAutomationPlans;
        }

        public void setMaxAutomationPlans(int maxAutomationPlans) {
            this.maxAutomationPlans = maxAutomationPlans;
        }
    }

    public static class Backpressure {
        private boolean enabled = true;
        private int maxTrackedScanJobs = 20;
        private int maxRunningScanJobs = 8;
        private int maxDirectScans = 4;
        private int maxAutomationPlans = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxTrackedScanJobs() {
            return maxTrackedScanJobs;
        }

        public void setMaxTrackedScanJobs(int maxTrackedScanJobs) {
            this.maxTrackedScanJobs = maxTrackedScanJobs;
        }

        public int getMaxRunningScanJobs() {
            return maxRunningScanJobs;
        }

        public void setMaxRunningScanJobs(int maxRunningScanJobs) {
            this.maxRunningScanJobs = maxRunningScanJobs;
        }

        public int getMaxDirectScans() {
            return maxDirectScans;
        }

        public void setMaxDirectScans(int maxDirectScans) {
            this.maxDirectScans = maxDirectScans;
        }

        public int getMaxAutomationPlans() {
            return maxAutomationPlans;
        }

        public void setMaxAutomationPlans(int maxAutomationPlans) {
            this.maxAutomationPlans = maxAutomationPlans;
        }
    }
}
