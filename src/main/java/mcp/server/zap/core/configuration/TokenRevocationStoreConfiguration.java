package mcp.server.zap.core.configuration;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.service.revocation.InMemoryTokenRevocationStore;
import mcp.server.zap.core.service.revocation.PostgresTokenRevocationStore;
import mcp.server.zap.core.service.revocation.TokenRevocationStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

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
                throw new IllegalStateException("JWT revocation backend 'postgres' requires "
                        + "JWT_REVOCATION_STORE_POSTGRES_URL or mcp.server.auth.jwt.revocation.postgres.url.");
            }
            log.info("JWT revocation store backend: postgres");
            return new PostgresTokenRevocationStore(properties.getPostgres());
        }

        if (!"in-memory".equals(backend)) {
            throw new IllegalArgumentException("Unsupported JWT revocation backend. Use 'in-memory' or 'postgres'.");
        }
        log.info("JWT revocation store backend: in-memory");
        return new InMemoryTokenRevocationStore();
    }

    /**
     * Normalize backend values and apply default in-memory mode.
     */
    private String normalize(String value) {
        if (value == null) {
            return "in-memory";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
