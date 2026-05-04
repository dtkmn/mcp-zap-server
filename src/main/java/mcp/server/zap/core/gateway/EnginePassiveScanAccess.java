package mcp.server.zap.core.gateway;

/**
 * Gateway-facing access contract for passive scan backlog state.
 */
public interface EnginePassiveScanAccess {

    PassiveScanSnapshot loadPassiveScanSnapshot();

    record PassiveScanSnapshot(int recordsToScan, int activeTasks, boolean scanOnlyInScope) {
        public boolean completed() {
            return recordsToScan == 0;
        }
    }
}
