package mcp.server.zap.core.configuration;

import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zaproxy.clientapi.core.ClientApi;

@Configuration
public class ZapApiConfig {

    /**
     * Build the shared ZAP client API instance from configured connection settings.
     */
    @Bean
    public ClientApi zapClientApi(
            @Value("${zap.server.url:localhost}") String zapApiUrl,
            @Value("${zap.server.port:8090}") int zapApiPort,
            @Value("${zap.server.apiKey}") String zapApiKey,
            @Value("${zap.server.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${zap.server.read-timeout-ms:10000}") int readTimeoutMs
    ) {
        return new TimeoutZapClientApi(zapApiUrl, zapApiPort, zapApiKey, connectTimeoutMs, readTimeoutMs);
    }

}
