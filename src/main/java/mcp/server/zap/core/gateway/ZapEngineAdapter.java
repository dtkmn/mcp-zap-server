package mcp.server.zap.core.gateway;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Gateway adapter metadata for the built-in ZAP engine.
 */
@Component
public class ZapEngineAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "zap";
    }

    @Override
    public String displayName() {
        return "OWASP ZAP";
    }

    @Override
    public Set<EngineCapability> supportedCapabilities() {
        return Set.of(
                EngineCapability.TARGET_IMPORT,
                EngineCapability.GUIDED_CRAWL,
                EngineCapability.GUIDED_ATTACK,
                EngineCapability.FINDINGS_READ,
                EngineCapability.REPORT_GENERATE,
                EngineCapability.FORM_AUTH_BOOTSTRAP,
                EngineCapability.HEADER_AUTH_REFERENCE
        );
    }
}
