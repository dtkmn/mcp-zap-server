package mcp.server.zap.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zaproxy.clientapi.core.ClientApi;

@Configuration
public class ZapApiConfig {

    @Bean
    public ClientApi zapClientApi(
            @Value("${zap.server.url:localhost}") String zapApiUrl,
            @Value("${zap.server.port:8090}") int zapApiPort,
            @Value("${zap.server.apiKey}") String zapApiKey
    ) {
        // The ZAP ClientApi uses default Java HTTP client settings
        // Timeout issues are typically caused by ZAP server-side configuration
        // Configure ZAP server timeouts via startup options in docker-compose.yml
        return new ClientApi(zapApiUrl, zapApiPort, zapApiKey);
    }

}
