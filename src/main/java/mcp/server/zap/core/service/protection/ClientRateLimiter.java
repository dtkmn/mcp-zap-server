package mcp.server.zap.core.service.protection;

import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.springframework.stereotype.Service;

/**
 * Simple in-memory token-bucket limiter keyed by client ID.
 */
@Service
public class ClientRateLimiter {
    private final AbuseProtectionProperties properties;
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();

    public ClientRateLimiter(AbuseProtectionProperties properties) {
        this.properties = properties;
    }

    public boolean tryConsume(String clientId) {
        if (!properties.isEnabled() || !properties.getRateLimit().isEnabled()) {
            return true;
        }

        return limiter.tryConsume(clientId, rateLimitPolicy());
    }

    public long retryAfterSeconds(String clientId) {
        if (!properties.isEnabled() || !properties.getRateLimit().isEnabled()) {
            return Math.max(1L, properties.getRetryAfterSeconds());
        }

        return limiter.retryAfterSeconds(clientId, rateLimitPolicy());
    }

    private TokenBucketRateLimiter.Policy rateLimitPolicy() {
        return new TokenBucketRateLimiter.Policy(
                properties.isEnabled() && properties.getRateLimit().isEnabled(),
                properties.getRateLimit().getCapacity(),
                properties.getRateLimit().getRefillTokens(),
                properties.getRateLimit().getRefillPeriodSeconds(),
                properties.getRateLimit().getMaxTrackedClients(),
                properties.getRetryAfterSeconds()
        );
    }
}
