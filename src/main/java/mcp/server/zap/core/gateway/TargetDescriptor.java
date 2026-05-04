package mcp.server.zap.core.gateway;

/**
 * Normalized gateway descriptor for a scan target independent of engine naming.
 */
public record TargetDescriptor(
        Kind kind,
        String baseUrl,
        String displayName
) {
    public enum Kind {
        WEB,
        API
    }
}
