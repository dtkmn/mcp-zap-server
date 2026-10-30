package mcp.server.zap.core.configuration;

import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "zap.scan.jobs.launch-safety")
public class ScanLaunchSafetyProperties {
    private String journalDirectory = Path.of(System.getProperty("user.home"), ".mcp-zap", "scan-launch-safety").toString();
}
