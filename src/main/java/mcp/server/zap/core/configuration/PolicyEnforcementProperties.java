package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rollout mode for runtime policy enforcement.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.policy")
public class PolicyEnforcementProperties {
    private Mode mode = Mode.OFF;
    private String bundle;
    private String bundleFile;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getBundle() {
        return bundle;
    }

    public void setBundle(String bundle) {
        this.bundle = bundle;
    }

    public String getBundleFile() {
        return bundleFile;
    }

    public void setBundleFile(String bundleFile) {
        this.bundleFile = bundleFile;
    }

    public enum Mode {
        OFF,
        DRY_RUN,
        ENFORCE
    }
}
