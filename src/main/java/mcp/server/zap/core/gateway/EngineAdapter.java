package mcp.server.zap.core.gateway;

import java.util.Set;

/**
 * Minimal gateway-facing contract for a scanning engine.
 */
public interface EngineAdapter {

    String engineId();

    String displayName();

    Set<EngineCapability> supportedCapabilities();

    default boolean supports(EngineCapability capability) {
        Set<EngineCapability> capabilities = supportedCapabilities();
        return capability != null && capabilities != null && capabilities.contains(capability);
    }
}
