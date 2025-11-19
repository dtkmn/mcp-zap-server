package mcp.server.zap.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for API key clients.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth")
public class ApiKeyProperties {

    /**
     * List of registered API key clients.
     */
    private List<ApiKeyClient> apiKeys = new ArrayList<>();

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
    }
}
