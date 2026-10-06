package mcp.server.zap.core.exception;

/**
 * Custom runtime exception to represent errors during interaction with the ZAP API.
 * Wrapping the original ClientApiException allows for more specific error handling
 * within the application without forcing callers to handle checked exceptions.
 */
public class ZapApiException extends RuntimeException {
    private final boolean mayHaveDispatched;

    /**
     * Constructs a new ZapApiException with the specified detail message and cause.
     *
     * @param message The detail message (which is saved for later retrieval by the getMessage() method).
     * @param cause   The cause (which is saved for later retrieval by the getCause() method).
     */
    public ZapApiException(String message, Throwable cause) {
        this(message, cause, true);
    }

    private ZapApiException(String message, Throwable cause, boolean mayHaveDispatched) {
        super(message, cause);
        this.mayHaveDispatched = mayHaveDispatched;
    }

    /**
     * Create a failure known to occur before the guarded engine operation starts.
     * Existing exceptions conservatively leave dispatch and its outcome uncertain.
     */
    public static ZapApiException beforeDispatch(String message, Throwable cause) {
        return new ZapApiException(message, cause, false);
    }

    /** Return true unless dispatch of the engine operation is known not to have occurred. */
    public boolean mayHaveDispatched() {
        return mayHaveDispatched;
    }

}
