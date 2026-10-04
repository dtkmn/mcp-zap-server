package mcp.server.zap.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Operator-confirmed shared filesystem for bounded, client-supplied API definitions. */
@Configuration
@ConfigurationProperties(prefix = "zap.openapi.content-import")
public class OpenApiContentImportProperties {
    private boolean enabled;
    private String localDirectory = "";
    private String zapDirectory = "";
    private int maxRetainedImports = 64;
    private int retentionMinutes = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLocalDirectory() { return localDirectory; }
    public void setLocalDirectory(String localDirectory) { this.localDirectory = localDirectory; }
    public String getZapDirectory() { return zapDirectory; }
    public void setZapDirectory(String zapDirectory) { this.zapDirectory = zapDirectory; }
    public int getMaxRetainedImports() { return maxRetainedImports; }
    public void setMaxRetainedImports(int maxRetainedImports) { this.maxRetainedImports = maxRetainedImports; }
    public int getRetentionMinutes() { return retentionMinutes; }
    public void setRetentionMinutes(int retentionMinutes) { this.retentionMinutes = retentionMinutes; }
}
