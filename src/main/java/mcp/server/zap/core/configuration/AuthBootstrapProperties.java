package mcp.server.zap.core.configuration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Controls how guided auth bootstrap accepts secrets.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth.bootstrap")
public class AuthBootstrapProperties {
    private boolean allowInlineSecrets = false;
    private List<String> allowedCredentialReferences = new ArrayList<>();

    public boolean isAllowInlineSecrets() {
        return allowInlineSecrets;
    }

    public void setAllowInlineSecrets(boolean allowInlineSecrets) {
        this.allowInlineSecrets = allowInlineSecrets;
    }

    public List<String> getAllowedCredentialReferences() {
        return allowedCredentialReferences;
    }

    public void setAllowedCredentialReferences(List<String> allowedCredentialReferences) {
        this.allowedCredentialReferences = allowedCredentialReferences == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedCredentialReferences);
    }
}
