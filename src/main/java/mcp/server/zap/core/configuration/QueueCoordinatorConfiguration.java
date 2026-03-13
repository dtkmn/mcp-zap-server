package mcp.server.zap.core.configuration;

import io.micrometer.core.instrument.MeterRegistry;
import mcp.server.zap.core.service.queue.leadership.PostgresAdvisoryLockQueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.QueueLeadershipCoordinator;
import mcp.server.zap.core.service.queue.leadership.SingleNodeQueueLeadershipCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueueCoordinatorProperties.class)
public class QueueCoordinatorConfiguration {
    private static final Logger log = LoggerFactory.getLogger(QueueCoordinatorConfiguration.class);

    /**
     * Resolve leadership coordinator backend (single-node or Postgres advisory lock).
     */
    @Bean
    QueueLeadershipCoordinator queueLeadershipCoordinator(
            QueueCoordinatorProperties properties,
            ObjectProvider<ScanJobStoreProperties> scanJobStorePropertiesProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        ScanJobStoreProperties scanJobStoreProperties =
                scanJobStorePropertiesProvider.getIfAvailable(ScanJobStoreProperties::new);
        String backend = normalize(properties.getBackend());
        if ("postgres-lock".equals(backend)) {
            QueueCoordinatorProperties.Postgres postgres =
                    buildEffectivePostgres(properties.getPostgres(), scanJobStoreProperties);
            if (postgres.getUrl() == null || postgres.getUrl().isBlank()) {
                log.warn("Queue coordinator backend is postgres-lock but URL is blank; using single-node coordinator");
                return new SingleNodeQueueLeadershipCoordinator();
            }
            log.info("Queue coordinator backend: postgres-lock");
            return new PostgresAdvisoryLockQueueLeadershipCoordinator(
                    properties.getNodeId(),
                    postgres,
                    meterRegistryProvider.getIfAvailable()
            );
        }

        if (!"single-node".equals(backend)) {
            log.warn("Unknown queue coordinator backend '{}'; using single-node coordinator", properties.getBackend());
        } else {
            log.info("Queue coordinator backend: single-node");
        }
        return new SingleNodeQueueLeadershipCoordinator();
    }

    /**
     * Merge coordinator and store Postgres settings with coordinator values preferred.
     */
    private QueueCoordinatorProperties.Postgres buildEffectivePostgres(
            QueueCoordinatorProperties.Postgres coordinatorPostgres,
            ScanJobStoreProperties scanJobStoreProperties
    ) {
        QueueCoordinatorProperties.Postgres effective = new QueueCoordinatorProperties.Postgres();
        effective.setUrl(hasText(coordinatorPostgres.getUrl())
                ? coordinatorPostgres.getUrl()
                : scanJobStoreProperties.getPostgres().getUrl());
        effective.setUsername(hasText(coordinatorPostgres.getUsername())
                ? coordinatorPostgres.getUsername()
                : scanJobStoreProperties.getPostgres().getUsername());
        effective.setPassword(hasText(coordinatorPostgres.getPassword())
                ? coordinatorPostgres.getPassword()
                : scanJobStoreProperties.getPostgres().getPassword());
        effective.setAdvisoryLockKey(coordinatorPostgres.getAdvisoryLockKey());
        effective.setHeartbeatIntervalMs(coordinatorPostgres.getHeartbeatIntervalMs());
        effective.setFailFast(coordinatorPostgres.isFailFast());
        return effective;
    }

    /**
     * Normalize coordinator backend values and apply default single-node mode.
     */
    private String normalize(String value) {
        if (value == null) {
            return "single-node";
        }
        return value.trim().toLowerCase();
    }

    /**
     * Return true when a value contains non-whitespace text.
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
