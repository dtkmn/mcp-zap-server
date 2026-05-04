package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Controls how guided auth bootstrap accepts secrets.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth.bootstrap")
public class AuthBootstrapProperties {
    private boolean allowInlineSecrets = false;

    public boolean isAllowInlineSecrets() {
        return allowInlineSecrets;
    }

    public void setAllowInlineSecrets(boolean allowInlineSecrets) {
        this.allowInlineSecrets = allowInlineSecrets;
    }
}
