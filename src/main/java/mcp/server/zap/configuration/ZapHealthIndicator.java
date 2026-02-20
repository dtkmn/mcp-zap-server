package mcp.server.zap.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ClientApi;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Health indicator that checks connectivity to the ZAP API.
 * Reports ZAP version when healthy.
 */
@Slf4j
@Component
public class ZapHealthIndicator implements ReactiveHealthIndicator {

    private final ClientApi zap;

    public ZapHealthIndicator(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() -> {
            try {
                // Check ZAP connectivity by getting version
                ApiResponseElement versionResponse = (ApiResponseElement) zap.core.version();
                String version = versionResponse.getValue();
                log.debug("ZAP health check passed. Version: {}", version);
                return Health.up()
                        .withDetail("status", "connected")
                        .build();
            } catch (Exception e) {
                log.warn("ZAP health check failed: {}", e.getMessage());
                return Health.down()
                        .withDetail("status", "disconnected")
                        .withDetail("error", "ZAP connectivity check failed")
                        .build();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
