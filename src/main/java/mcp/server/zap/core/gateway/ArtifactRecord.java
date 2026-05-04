package mcp.server.zap.core.gateway;

/**
 * Shared gateway record for generated evidence or report artifacts.
 */
public record ArtifactRecord(
        String engineId,
        String artifactId,
        String artifactType,
        String location,
        String mediaType,
        TargetDescriptor target
) {
}
