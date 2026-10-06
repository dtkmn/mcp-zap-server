package mcp.server.zap.core.configuration;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.service.ZapInitializationService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Health indicator that requires ZAP connectivity and configured network defaults.
 */
@Slf4j
@Component
public class ZapHealthIndicator implements ReactiveHealthIndicator {

    private final ZapInitializationService initializationService;

    /**
     * Build-time dependency injection constructor.
     */
    public ZapHealthIndicator(ZapInitializationService initializationService) {
        this.initializationService = initializationService;
    }

    /**
     * Reconcile network defaults on the blocking-I/O scheduler before reporting ready.
     */
    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() -> {
            try {
                String version = initializationService.ensureInitialized();
                log.debug("ZAP health check passed. Version: {}", version);
                return Health.up()
                        .withDetail("status", "connected")
                        .build();
            } catch (Exception e) {
                log.warn("ZAP health check failed: {}", e.getMessage());
                return Health.down()
                        .withDetail("status", "not-ready")
                        .withDetail("error", "ZAP connectivity or required network configuration check failed")
                        .build();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
