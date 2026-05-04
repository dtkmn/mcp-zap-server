package mcp.server.zap.core.exception;

/**
 * Raised when a runtime policy hook denies a tool call in enforce mode.
 */
public class ToolPolicyDeniedException extends RuntimeException {
    public ToolPolicyDeniedException(String message) {
        super(message);
    }
}
