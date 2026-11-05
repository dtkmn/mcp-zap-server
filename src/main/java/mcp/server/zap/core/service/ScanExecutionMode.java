package mcp.server.zap.core.service;

/** Execution discriminators for signed operation handles; new scans always use QUEUE. */
public enum ScanExecutionMode {
    // DIRECT remains supported only for previously issued operation handles.
    DIRECT,
    QUEUE
}
