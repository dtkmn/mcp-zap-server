package mcp.server.zap.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@Getter
@Setter
@ConfigurationProperties(prefix = "zap.scan.queue.coordinator")
public class QueueCoordinatorProperties {

    private String backend = "single-node";
    private String nodeId = UUID.randomUUID().toString();
    private final Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Postgres {
        private String url = "";
        private String username = "";
        private String password = "";
        private long advisoryLockKey = 861001L;
        private long heartbeatIntervalMs = 5000L;
        private boolean failFast = false;
    }
}
