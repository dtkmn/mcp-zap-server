package mcp.server.zap.core.service;

import java.sql.DriverManager;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.service.jobstore.PostgresScanJobStore;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Runs the same guided lifecycle contract against an isolated real PostgreSQL store. */
@Tag("docker")
@Testcontainers
class PostgresGuidedQueuedScanIntegrationTest extends GuidedQueuedScanIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withReuse(false)
            .withCommand("postgres", "-c", "shared_buffers=32MB", "-c", "max_connections=30")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(256L * 1024 * 1024).withNanoCPUs(1_000_000_000L));

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @Override
    protected ScanJobStore newStore() throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM scan_jobs");
        }
        ScanJobStoreProperties.Postgres properties = new ScanJobStoreProperties.Postgres();
        properties.setUrl(POSTGRES.getJdbcUrl());
        properties.setUsername(POSTGRES.getUsername());
        properties.setPassword(POSTGRES.getPassword());
        properties.setFailFast(true);
        return new PostgresScanJobStore(properties, new ObjectMapper());
    }
}
