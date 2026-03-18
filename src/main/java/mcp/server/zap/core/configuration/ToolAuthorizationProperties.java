package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for MCP tool-level authorization.
 */
@Configuration
@ConfigurationProperties(prefix = "mcp.server.security.authorization")
public class ToolAuthorizationProperties {

    /**
     * Authorization mode:
     * - off: do not perform scope checks
     * - warn: log missing-scope events but allow execution
     * - enforce: reject insufficient-scope calls with HTTP 403
     */
    private Mode mode = Mode.ENFORCE;

    /**
     * Allow wildcard "*" scopes as a backward-compatible super-scope.
     */
    private boolean allowWildcard = true;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isAllowWildcard() {
        return allowWildcard;
    }

    public void setAllowWildcard(boolean allowWildcard) {
        this.allowWildcard = allowWildcard;
    }

    public enum Mode {
        OFF,
        WARN,
        ENFORCE
    }
}
