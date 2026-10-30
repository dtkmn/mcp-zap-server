package mcp.server.zap.core.gateway;

/** Hooks at the native scan-start boundary, after preparation and before its external effect. */
public interface NativeScanLaunch {
    NativeScanLaunch UNTRACKED = new NativeScanLaunch() { };

    default void beforeDispatch() { }

    default void accepted(String scanId) { }
}
