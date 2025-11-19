package mcp.server.zap.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for JWT authentication.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mcp.server.auth.jwt")
public class JwtProperties {

    /**
     * Secret key for signing JWT tokens (must be at least 256 bits / 32 bytes).
     */
    private String secret;

    /**
     * Access token expiry in seconds (default: 1 hour).
     */
    private long accessTokenExpiry = 3600;

    /**
     * Refresh token expiry in seconds (default: 7 days).
     */
    private long refreshTokenExpiry = 604800;

    /**
     * JWT issuer identifier.
     */
    private String issuer = "mcp-zap-server";

    /**
     * Enable JWT authentication.
     */
    private boolean enabled = true;
}
