package mcp.server.zap.core.configuration;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Controls how guided auth bootstrap accepts secrets.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth.bootstrap")
public class AuthBootstrapProperties {
    private List<Profile> profiles = new ArrayList<>();

    public List<Profile> getProfiles() {
        return profiles;
    }

    public void setProfiles(List<Profile> profiles) {
        this.profiles = profiles == null ? new ArrayList<>() : new ArrayList<>(profiles);
    }

    /**
     * Operator-owned authentication profile. MCP callers select only the profile ID and target URL;
     * credential and authentication configuration remain deployment configuration.
     */
    @Getter
    @Setter
    public static class Profile {
        private String id;
        private String kind;
        private String allowedOrigin;
        private String credentialReference;
        private String loginUrl;
        private String username;
        private String zapUserName;
        private String usernameField;
        private String passwordField;
        private String headerName;
        private String loggedInIndicatorRegex;
        private String loggedOutIndicatorRegex;
    }
}
