package mcp.server.zap.core.configuration;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import mcp.server.zap.core.service.revocation.PostgresTokenRevocationStore;
import mcp.server.zap.core.service.revocation.TokenRevocationStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(TokenRevocationStoreProperties.class)
public class TokenRevocationStoreConfiguration {

    /**
     * Resolve token revocation backend (in-memory or Postgres) from configuration.
     */
    @Bean
    TokenRevocationStore tokenRevocationStore(TokenRevocationStoreProperties properties) {
        String backend = normalize(properties.getBackend());
        if ("postgres".equals(backend)) {
            if (properties.getPostgres().getUrl() == null || properties.getPostgres().getUrl().isBlank()) {
                log.warn("JWT revocation backend is set to postgres but URL is blank; using in-memory revocation store");
                return new InMemoryTokenRevocationStore();
            }
            try {
                log.info("JWT revocation store backend: postgres");
                return new PostgresTokenRevocationStore(properties.getPostgres());
            } catch (RuntimeException e) {
                if (properties.getPostgres().isFailFast()) {
                    throw e;
                }
                log.warn("JWT revocation postgres initialization failed (using in-memory): {}", e.getMessage());
                return new InMemoryTokenRevocationStore();
            }
        }

        if (!"in-memory".equals(backend)) {
            log.warn("Unknown JWT revocation backend '{}'; using in-memory revocation store", properties.getBackend());
        } else {
            log.info("JWT revocation store backend: in-memory");
        }
        return new InMemoryTokenRevocationStore();
    }

    /**
     * Normalize backend values and apply default in-memory mode.
     */
    private String normalize(String value) {
        if (value == null) {
            return "in-memory";
        }
        return value.trim().toLowerCase();
    }
}
