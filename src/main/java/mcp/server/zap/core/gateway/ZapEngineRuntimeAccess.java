package mcp.server.zap.core.gateway;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of runtime health and startup configuration.
 */
@Slf4j
@Component
public class ZapEngineRuntimeAccess implements EngineRuntimeAccess {

    private final ClientApi zap;

    public ZapEngineRuntimeAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public String readVersion() {
        try {
            ApiResponseElement versionResponse = (ApiResponseElement) zap.core.version();
            return versionResponse.getValue();
        } catch (ClientApiException e) {
            throw new ZapApiException("ZAP connectivity check failed", e);
        }
    }

    @Override
    public void applyNetworkDefaults(NetworkDefaults defaults) {
        try {
            if (!defaults.userAgent().equals(valueOf(zap.network.getDefaultUserAgent()))) {
                zap.network.setDefaultUserAgent(defaults.userAgent());
                log.info("Configured ZAP default user agent");
            }
            String connectionTimeout = String.valueOf(defaults.connectionTimeoutInSecs());
            if (!connectionTimeout.equals(valueOf(zap.network.getConnectionTimeout()))) {
                zap.network.setConnectionTimeout(connectionTimeout);
                log.info("Configured ZAP target connection timeout to {} seconds", defaults.connectionTimeoutInSecs());
            }
        } catch (ClientApiException e) {
            throw new ZapApiException("ZAP required network configuration failed", e);
        }
        setDnsTtlSuccessfulQueries(defaults.dnsTtlSuccessfulQueries());
    }

    private String valueOf(ApiResponse response) throws ClientApiException {
        if (response instanceof ApiResponseElement element && element.getValue() != null) {
            return element.getValue();
        }
        throw new ClientApiException("ZAP network setting did not return a value");
    }

    private void setDnsTtlSuccessfulQueries(int dnsTtlSuccessfulQueries) {
        try {
            String dnsTtl = String.valueOf(dnsTtlSuccessfulQueries);
            if (!dnsTtl.equals(valueOf(zap.network.getDnsTtlSuccessfulQueries()))) {
                zap.network.setDnsTtlSuccessfulQueries(dnsTtl);
                log.info("Configured ZAP DNS TTL for successful queries to {} seconds", dnsTtlSuccessfulQueries);
            }
        } catch (ClientApiException e) {
            if ("bad_view".equals(e.getCode()) || "bad_action".equals(e.getCode())) {
                log.debug("ZAP does not support the optional DNS TTL setting");
                return;
            }
            throw new ZapApiException("ZAP DNS network configuration failed", e);
        }
    }
}
