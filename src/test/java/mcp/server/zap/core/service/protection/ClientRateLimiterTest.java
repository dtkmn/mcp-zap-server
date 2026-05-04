package mcp.server.zap.core.service.protection;

import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientRateLimiterTest {

    @Test
    void deniesRequestsAfterBucketIsExhaustedUntilRefill() {
        AbuseProtectionProperties properties = new AbuseProtectionProperties();
        properties.getRateLimit().setCapacity(1);
        properties.getRateLimit().setRefillTokens(1);
        properties.getRateLimit().setRefillPeriodSeconds(3600);

        ClientRateLimiter limiter = new ClientRateLimiter(properties);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isFalse();
        assertThat(limiter.retryAfterSeconds("client-a")).isGreaterThanOrEqualTo(1L);
    }
}
