package mcp.server.zap.core.gateway;

/**
 * The engine explicitly rejected a scan launch because it is busy; no scan was started.
 */
public class EngineBusyException extends RuntimeException {

    public EngineBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
