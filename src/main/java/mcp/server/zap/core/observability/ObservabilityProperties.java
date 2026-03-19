package mcp.server.zap.core.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Public observability settings for audit retention and related runtime behavior.
 */
@Component
@ConfigurationProperties(prefix = "mcp.server.observability")
public class ObservabilityProperties {
    private final Audit audit = new Audit();

    public Audit getAudit() {
        return audit;
    }

    public static final class Audit {
        private boolean enabled = true;
        private int maxEvents = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }
    }
}
