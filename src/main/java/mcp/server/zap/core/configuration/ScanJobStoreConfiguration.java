package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                log.warn("Scan job store backend is set to postgres but URL is blank; using in-memory scan job store");
                return new InMemoryScanJobStore();
            }
            try {
                log.info("Scan job store backend: postgres");
                return new PostgresScanJobStore(properties.getPostgres(), objectMapper);
            } catch (RuntimeException e) {
                if (properties.getPostgres().isFailFast()) {
                    throw e;
                }
                log.warn("Scan job store postgres initialization failed (using in-memory): {}", e.getMessage());
                return new InMemoryScanJobStore();
            }
        }

        if (!"in-memory".equals(backend)) {
            log.warn("Unknown scan job store backend '{}'; using in-memory scan job store", properties.getBackend());
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
