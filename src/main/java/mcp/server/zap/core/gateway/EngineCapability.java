package mcp.server.zap.core.gateway;

/**
 * Coarse-grained engine capabilities exposed through the gateway layer.
 */
public enum EngineCapability {
    TARGET_IMPORT,
    GUIDED_CRAWL,
    GUIDED_ATTACK,
    FINDINGS_READ,
    REPORT_GENERATE,
    FORM_AUTH_BOOTSTRAP,
    HEADER_AUTH_REFERENCE
}
