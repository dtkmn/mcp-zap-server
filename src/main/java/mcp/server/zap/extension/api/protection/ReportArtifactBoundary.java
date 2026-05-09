package mcp.server.zap.extension.api.protection;

import java.nio.file.Path;

/**
 * Extension API boundary for tenant or workspace scoped report artifact directories.
 */
public interface ReportArtifactBoundary {

    /**
     * Resolve the directory used when generating a report for the current requester.
     */
    default Path resolveWriteDirectory(Path defaultDirectory) {
        return defaultDirectory;
    }

    /**
     * Resolve the readable report root for the current requester.
     */
    default Path resolveReadDirectory(Path defaultDirectory) {
        return defaultDirectory;
    }
}
