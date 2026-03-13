package mcp.server.zap.core.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "mcp.server.auth.jwt.revocation")
public class TokenRevocationStoreProperties {

    private String backend = "in-memory";
    private final Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Postgres {
        private String url = "";
        private String username = "";
        private String password = "";
        private String tableName = "jwt_token_revocation";
        private boolean failFast = false;
    }
}
