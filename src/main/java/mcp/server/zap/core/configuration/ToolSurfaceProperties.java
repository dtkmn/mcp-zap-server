package mcp.server.zap.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls which MCP tool surface is exposed by default.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.server.tools")
public class ToolSurfaceProperties {

    private Surface surface = Surface.GUIDED;

    public boolean expert() {
        return surface == Surface.EXPERT;
    }

    public enum Surface {
        GUIDED,
        EXPERT
    }
}
