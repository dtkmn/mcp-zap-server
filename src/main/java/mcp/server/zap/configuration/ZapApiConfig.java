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
        return new ClientApi(zapApiUrl, zapApiPort, zapApiKey);
    }

}
