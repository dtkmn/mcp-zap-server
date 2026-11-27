package mcp.server.zap.core.gateway;

import mcp.server.zap.core.exception.ZapApiException;
import org.zaproxy.clientapi.core.ClientApiException;

/** The scanner explicitly rejected the numeric ID sent to its status endpoint. */
public final class ScanTrackingLostException extends ZapApiException {
    private final String scanId;

    public ScanTrackingLostException(String scanId, ClientApiException cause) {
        super("ZAP no longer recognizes scan ID " + scanId, cause);
        this.scanId = scanId;
    }

    public String scanId() {
        return scanId;
    }
}
