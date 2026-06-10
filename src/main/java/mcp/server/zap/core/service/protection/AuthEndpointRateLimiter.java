package mcp.server.zap.core.service.protection;

import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.server.zap.core.configuration.AuthRateLimitProperties;
import org.springframework.stereotype.Service;

/**
 * In-memory token-bucket limiter for unauthenticated auth exchange endpoints.
 */
@Service
public class AuthEndpointRateLimiter {
    private final AuthRateLimitProperties properties;
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();

    public AuthEndpointRateLimiter(AuthRateLimitProperties properties) {
        this.properties = properties;
    }

    public boolean tryConsume(String key) {
        if (!properties.isEnabled()) {
            return true;
        }

        return limiter.tryConsume(key, rateLimitPolicy());
    }

    public long retryAfterSeconds(String key) {
        if (!properties.isEnabled()) {
            return 1L;
        }

        return limiter.retryAfterSeconds(key, rateLimitPolicy());
    }

    private TokenBucketRateLimiter.Policy rateLimitPolicy() {
        return new TokenBucketRateLimiter.Policy(
                properties.isEnabled(),
                properties.getCapacity(),
                properties.getRefillTokens(),
                properties.getRefillPeriodSeconds(),
                properties.getMaxTrackedKeys(),
                1L
        );
    }
}
