package mcp.server.zap.core.service.protection;

import static org.assertj.core.api.Assertions.assertThat;

import mcp.server.zap.core.configuration.AuthRateLimitProperties;
import org.junit.jupiter.api.Test;

class AuthEndpointRateLimiterTest {

    @Test
    void deniesRequestsAfterEndpointBucketIsExhausted() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setCapacity(1);
        properties.setRefillTokens(1);
        properties.setRefillPeriodSeconds(3600);

        AuthEndpointRateLimiter limiter = new AuthEndpointRateLimiter(properties);

        assertThat(limiter.tryConsume("127.0.0.1")).isTrue();
        assertThat(limiter.tryConsume("127.0.0.1")).isFalse();
        assertThat(limiter.retryAfterSeconds("127.0.0.1")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void disabledEndpointLimitAllowsRequests() {
        AuthRateLimitProperties properties = new AuthRateLimitProperties();
        properties.setEnabled(false);

        AuthEndpointRateLimiter limiter = new AuthEndpointRateLimiter(properties);

        assertThat(limiter.tryConsume("127.0.0.1")).isTrue();
        assertThat(limiter.retryAfterSeconds("127.0.0.1")).isEqualTo(1L);
    }
}
