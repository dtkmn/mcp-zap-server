package mcp.server.zap.core.configuration;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Operator-owned maintenance policy. Callers cannot supply destinations or installation files. */
@Configuration
@ConfigurationProperties(prefix = "zap.addons")
public class AddonMaintenanceProperties {
    private boolean updatesEnabled;
    private boolean exclusiveEngine;
    private int controllerReplicas = 1;
    private List<String> allowedAddonIds = List.of("pscanrules");
    private List<String> operatorClientIds = List.of();
    private String stateDirectory = System.getProperty("user.home") + "/.mcp-zap/addon-maintenance";

    public boolean isUpdatesEnabled() { return updatesEnabled; }
    public void setUpdatesEnabled(boolean value) { updatesEnabled = value; }
    public boolean isExclusiveEngine() { return exclusiveEngine; }
    public void setExclusiveEngine(boolean value) { exclusiveEngine = value; }
    public int getControllerReplicas() { return controllerReplicas; }
    public void setControllerReplicas(int value) { controllerReplicas = value; }
    public List<String> getAllowedAddonIds() { return allowedAddonIds; }
    public void setAllowedAddonIds(List<String> value) { allowedAddonIds = value == null ? List.of() : List.copyOf(value); }
    public List<String> getOperatorClientIds() { return operatorClientIds; }
    public void setOperatorClientIds(List<String> value) { operatorClientIds = value == null ? List.of() : List.copyOf(value); }
    public String getStateDirectory() { return stateDirectory; }
    public void setStateDirectory(String value) { stateDirectory = value; }
}
