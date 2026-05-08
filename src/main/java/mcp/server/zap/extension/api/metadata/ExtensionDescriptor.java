package mcp.server.zap.extension.api.metadata;

import java.util.Map;

/**
 * Public extension identity and compatibility metadata.
 */
public record ExtensionDescriptor(
        String id,
        String name,
        String apiVersion,
        String type,
        boolean privateExtension,
        Map<String, String> attributes
) {
    public ExtensionDescriptor {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
