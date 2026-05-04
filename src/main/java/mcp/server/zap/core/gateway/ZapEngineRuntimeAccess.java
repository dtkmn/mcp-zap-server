package mcp.server.zap.core.gateway;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
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
        setDefaultUserAgent(defaults.userAgent());
        setConnectionTimeout(defaults.connectionTimeoutInSecs());
        setDnsTtlSuccessfulQueries(defaults.dnsTtlSuccessfulQueries());
    }

    private void setDefaultUserAgent(String userAgent) {
        try {
            zap.network.setDefaultUserAgent(userAgent);
            log.info("Set User-Agent: {}", userAgent);
        } catch (ClientApiException e) {
            log.warn("Could not set User-Agent: {}", e.getMessage());
        }
    }

    private void setConnectionTimeout(int connectionTimeoutInSecs) {
        try {
            zap.network.setConnectionTimeout(String.valueOf(connectionTimeoutInSecs));
            log.info("Set connection timeout to {} seconds", connectionTimeoutInSecs);
        } catch (ClientApiException e) {
            log.warn("Could not set connection timeout: {}", e.getMessage());
        }
    }

    private void setDnsTtlSuccessfulQueries(int dnsTtlSuccessfulQueries) {
        try {
            zap.network.setDnsTtlSuccessfulQueries(String.valueOf(dnsTtlSuccessfulQueries));
            log.info("Set DNS TTL for successful queries to {} seconds", dnsTtlSuccessfulQueries);
        } catch (ClientApiException e) {
            log.debug("Could not set DNS TTL: {}", e.getMessage());
        }
    }
}
