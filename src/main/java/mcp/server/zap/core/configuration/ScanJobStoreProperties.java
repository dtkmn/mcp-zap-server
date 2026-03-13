package mcp.server.zap.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "zap.scan.jobs.store")
public class ScanJobStoreProperties {

    private String backend = "in-memory";
    private final Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Postgres {
        private String url = "";
        private String username = "";
        private String password = "";
        private String tableName = "scan_jobs";
        private boolean failFast = false;
    }
}
