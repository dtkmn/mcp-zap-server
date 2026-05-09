package mcp.server.zap.core.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for API key clients.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth")
public class ApiKeyProperties {

    /**
     * List of registered API key clients.
     */
    private List<ApiKeyClient> apiKeys = new ArrayList<>();

    public List<ApiKeyClient> getApiKeys() {
        return List.copyOf(apiKeys);
    }

    public void setApiKeys(List<ApiKeyClient> apiKeys) {
        this.apiKeys = apiKeys == null ? new ArrayList<>() : new ArrayList<>(apiKeys);
    }

    @Data
    public static class ApiKeyClient {
        /**
         * The API key value.
         */
        private String key;

        /**
         * Client identifier.
         */
        private String clientId;

        /**
         * Client display name.
         */
        private String name;

        /**
         * Scopes/permissions granted to this client.
         * Use "*" for all permissions.
         */
        private List<String> scopes = List.of("*");

        /**
         * Optional workspace identifier shared by one or more clients.
         * When omitted, the clientId becomes the effective workspace boundary.
         */
        private String workspaceId;
    }
}
