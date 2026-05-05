package mcp.server.zap.core.gateway;

/**
 * Fail-closed error for operations the configured scanning engine cannot perform.
 */
public class UnsupportedEngineCapabilityException extends IllegalStateException {
    private final String engineId;
    private final String engineDisplayName;
    private final EngineCapability capability;
    private final String operationLabel;

    public UnsupportedEngineCapabilityException(String engineId,
                                                String engineDisplayName,
                                                EngineCapability capability,
                                                String operationLabel) {
        super("Engine '" + engineDisplayName + "' does not support " + operationLabel + ".");
        this.engineId = engineId;
        this.engineDisplayName = engineDisplayName;
        this.capability = capability;
        this.operationLabel = operationLabel;
    }

    public String engineId() {
        return engineId;
    }

    public String engineDisplayName() {
        return engineDisplayName;
    }

    public EngineCapability capability() {
        return capability;
    }

    public String operationLabel() {
        return operationLabel;
    }
}
