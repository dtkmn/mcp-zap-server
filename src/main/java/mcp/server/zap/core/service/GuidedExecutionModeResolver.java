package mcp.server.zap.core.service;

/** Execution discriminators for signed operation handles; new guided scans always use QUEUE. */
public final class GuidedExecutionModeResolver {
    private GuidedExecutionModeResolver() { }

    // DIRECT remains supported only for previously issued operation handles.
    public enum ExecutionMode {
        DIRECT,
        QUEUE
    }
}
