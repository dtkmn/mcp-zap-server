package mcp.server.zap.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "zap.scan.history")
public class ScanHistoryLedgerProperties {

    /**
     * Empty means inherit the scan-job store backend. Supported values are in-memory and postgres.
     */
    private String backend = "";
    private int retentionDays = 180;
    private int maxListEntries = 50;
    private int maxExportEntries = 500;
    private final Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Postgres {
        private String url = "";
        private String username = "";
        private String password = "";
        private String tableName = "scan_history_entries";
        private boolean failFast = false;
    }
}
