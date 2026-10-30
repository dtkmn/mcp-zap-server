package mcp.server.zap.core.configuration;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.service.jobstore.InMemoryScanJobStore;
import mcp.server.zap.core.service.jobstore.PostgresScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(ScanJobStoreProperties.class)
public class ScanJobStoreConfiguration {

    @Bean
    ScanJobStore scanJobStore(ScanJobStoreProperties properties, ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        String backend = normalize(properties.getBackend());
        if ("postgres".equals(backend)) {
            if (properties.getPostgres().getUrl() == null || properties.getPostgres().getUrl().isBlank()) {
                throw new IllegalStateException("Postgres scan job storage requires its URL; refusing an in-memory fallback that could lose launch ownership");
            }
            try {
                log.info("Scan job store backend: postgres");
                return new PostgresScanJobStore(properties.getPostgres(), objectMapper);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Postgres scan job initialization failed; launch ownership must not fall back to memory", e);
            }
        }

        if (!"in-memory".equals(backend)) {
            throw new IllegalArgumentException("Unknown scan job store backend: " + properties.getBackend());
        } else {
            log.info("Scan job store backend: in-memory");
        }
        return new InMemoryScanJobStore();
    }

    private String normalize(String value) {
        if (value == null) {
            return "in-memory";
        }
        return value.trim().toLowerCase();
    }
}
