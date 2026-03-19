package mcp.server.zap.core.service.protection;

import mcp.server.zap.core.logging.RequestLogContext;
import org.slf4j.MDC;

/**
 * Thread-local request identity used by synchronous tool implementations.
 * Values are propagated across Reactor operators via registered thread-local
 * accessors.
 */
public final class RequestIdentityHolder {
    public static final String CLIENT_ID_KEY = "asg.request.client-id";
    public static final String WORKSPACE_ID_KEY = "asg.request.workspace-id";

    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> WORKSPACE_ID = new ThreadLocal<>();

    private RequestIdentityHolder() {
    }

    public static void set(String clientId, String workspaceId) {
        setClientId(clientId);
        setWorkspaceId(workspaceId);
    }

    public static String currentClientId() {
        return CLIENT_ID.get();
    }

    public static String currentWorkspaceId() {
        return WORKSPACE_ID.get();
    }

    public static void setClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            CLIENT_ID.remove();
            MDC.remove(RequestLogContext.CLIENT_ID_MDC_KEY);
            return;
        }
        String normalized = clientId.trim();
        CLIENT_ID.set(normalized);
        MDC.put(RequestLogContext.CLIENT_ID_MDC_KEY, normalized);
    }

    public static void setWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            WORKSPACE_ID.remove();
            MDC.remove(RequestLogContext.WORKSPACE_ID_MDC_KEY);
            return;
        }
        String normalized = workspaceId.trim();
        WORKSPACE_ID.set(normalized);
        MDC.put(RequestLogContext.WORKSPACE_ID_MDC_KEY, normalized);
    }

    public static void clearClientId() {
        CLIENT_ID.remove();
        MDC.remove(RequestLogContext.CLIENT_ID_MDC_KEY);
    }

    public static void clearWorkspaceId() {
        WORKSPACE_ID.remove();
        MDC.remove(RequestLogContext.WORKSPACE_ID_MDC_KEY);
    }

    public static void clear() {
        clearClientId();
        clearWorkspaceId();
    }
}
