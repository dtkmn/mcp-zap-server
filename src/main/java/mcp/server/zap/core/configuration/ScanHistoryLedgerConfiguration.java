package mcp.server.zap.core.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.history.InMemoryScanHistoryStore;
import mcp.server.zap.core.history.PostgresScanHistoryStore;
import mcp.server.zap.core.history.ScanHistoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(ScanHistoryLedgerProperties.class)
public class ScanHistoryLedgerConfiguration {

    @Bean
    ScanHistoryStore scanHistoryStore(ScanHistoryLedgerProperties properties,
                                      ScanJobStoreProperties scanJobStoreProperties,
                                      ObjectProvider<ObjectMapper> objectMapperProvider) {
        String backend = normalize(hasText(properties.getBackend())
                ? properties.getBackend()
                : scanJobStoreProperties.getBackend());
        if ("postgres".equals(backend)) {
            ScanHistoryLedgerProperties.Postgres effectivePostgres =
                    effectivePostgresProperties(properties.getPostgres(), scanJobStoreProperties.getPostgres());
            if (!hasText(effectivePostgres.getUrl())) {
                log.warn("Scan history backend is set to postgres but URL is blank; using in-memory scan history store");
                return new InMemoryScanHistoryStore();
            }
            try {
                log.info("Scan history backend: postgres");
                ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
                return new PostgresScanHistoryStore(effectivePostgres, objectMapper);
            } catch (RuntimeException e) {
                if (effectivePostgres.isFailFast()) {
                    throw e;
                }
                log.warn("Scan history postgres initialization failed (using in-memory): {}", e.getMessage());
                return new InMemoryScanHistoryStore();
            }
        }

        if (!"in-memory".equals(backend)) {
            log.warn("Unknown scan history backend '{}'; using in-memory scan history store", backend);
        } else {
            log.info("Scan history backend: in-memory");
        }
        return new InMemoryScanHistoryStore();
    }

    private ScanHistoryLedgerProperties.Postgres effectivePostgresProperties(
            ScanHistoryLedgerProperties.Postgres historyPostgres,
            ScanJobStoreProperties.Postgres scanJobPostgres
    ) {
        ScanHistoryLedgerProperties.Postgres effective = new ScanHistoryLedgerProperties.Postgres();
        effective.setUrl(hasText(historyPostgres.getUrl()) ? historyPostgres.getUrl() : scanJobPostgres.getUrl());
        effective.setUsername(hasText(historyPostgres.getUsername())
                ? historyPostgres.getUsername()
                : scanJobPostgres.getUsername());
        effective.setPassword(hasText(historyPostgres.getPassword())
                ? historyPostgres.getPassword()
                : scanJobPostgres.getPassword());
        effective.setTableName(hasText(historyPostgres.getTableName())
                ? historyPostgres.getTableName()
                : "scan_history_entries");
        effective.setFailFast(historyPostgres.isFailFast() || scanJobPostgres.isFailFast());
        return effective;
    }

    private String normalize(String value) {
        if (!hasText(value)) {
            return "in-memory";
        }
        return value.trim().toLowerCase();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
