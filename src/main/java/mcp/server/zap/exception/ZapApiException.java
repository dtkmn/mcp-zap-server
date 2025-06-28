package mcp.server.zap.exception;

/**
 * Custom runtime exception to represent errors during interaction with the ZAP API.
 * Wrapping the original ClientApiException allows for more specific error handling
 * within the application without forcing callers to handle checked exceptions.
 */
public class ZapApiException extends RuntimeException {

    /**
     * Constructs a new ZapApiException with the specified detail message and cause.
     *
     * @param message The detail message (which is saved for later retrieval by the getMessage() method).
     * @param cause   The cause (which is saved for later retrieval by the getCause() method).
     */
    public ZapApiException(String message, Throwable cause) {
        super(message, cause);
    }

}
