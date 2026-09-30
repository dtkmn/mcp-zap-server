package mcp.server.zap.core.service.protection;

import mcp.gateway.core.rate.TokenBucketRateLimiter;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientRateLimiterTest {

    @Test
    void deniesRequestsAfterBucketIsExhaustedUntilRefill() {
        AbuseProtectionProperties properties = oneTokenProperties();
        ClientRateLimiter limiter = new ClientRateLimiter(properties);

        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.tryConsume("client-a")).isFalse();
        assertThat(limiter.retryAfterSeconds("client-a")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void attemptReturnsConsumptionAndRetryDelayTogether() {
        ClientRateLimiter limiter = new ClientRateLimiter(oneTokenProperties());

        assertThat(limiter.attempt("client-a")).isEqualTo(new TokenBucketRateLimiter.Attempt(true, 0L));

        TokenBucketRateLimiter.Attempt rejected = limiter.attempt("client-a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 3600L);
    }

    @Test
    void consumingOneClientDoesNotConsumeAnotherClientsToken() {
        ClientRateLimiter limiter = new ClientRateLimiter(oneTokenProperties());

        assertThat(limiter.attempt("client-a").allowed()).isTrue();
        assertThat(limiter.attempt("client-a").allowed()).isFalse();
        assertThat(limiter.attempt("client-b")).isEqualTo(new TokenBucketRateLimiter.Attempt(true, 0L));
        assertThat(limiter.attempt("client-b").allowed()).isFalse();
    }

    @Test
    void trackedClientLimitReturnsFallbackWithoutResettingExistingClient() {
        AbuseProtectionProperties properties = oneTokenProperties();
        properties.getRateLimit().setMaxTrackedClients(1);
        ClientRateLimiter limiter = new ClientRateLimiter(properties);

        assertThat(limiter.attempt("client-a").allowed()).isTrue();
        assertThat(limiter.attempt("client-b")).isEqualTo(new TokenBucketRateLimiter.Attempt(false, 1L));
        assertThat(limiter.attempt("client-c")).isEqualTo(new TokenBucketRateLimiter.Attempt(false, 1L));

        TokenBucketRateLimiter.Attempt existingClient = limiter.attempt("client-a");
        assertThat(existingClient.allowed()).isFalse();
        assertThat(existingClient.retryAfterSeconds()).isBetween(1L, 3600L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disablingProtectionOrRateLimitingDoesNotConsumeTokens(boolean disableAllProtection) {
        AbuseProtectionProperties properties = oneTokenProperties();
        properties.setRetryAfterSeconds(23L);
        properties.setEnabled(!disableAllProtection);
        properties.getRateLimit().setEnabled(disableAllProtection);
        ClientRateLimiter limiter = new ClientRateLimiter(properties);

        assertThat(limiter.attempt("client-a")).isEqualTo(new TokenBucketRateLimiter.Attempt(true, 0L));
        assertThat(limiter.attempt("client-a")).isEqualTo(new TokenBucketRateLimiter.Attempt(true, 0L));
        assertThat(limiter.tryConsume("client-a")).isTrue();
        assertThat(limiter.retryAfterSeconds("client-a")).isEqualTo(23L);

        properties.setEnabled(true);
        properties.getRateLimit().setEnabled(true);

        assertThat(limiter.attempt("client-a")).isEqualTo(new TokenBucketRateLimiter.Attempt(true, 0L));
        assertThat(limiter.attempt("client-a").allowed()).isFalse();
    }

    private AbuseProtectionProperties oneTokenProperties() {
        AbuseProtectionProperties properties = new AbuseProtectionProperties();
        properties.getRateLimit().setCapacity(1);
        properties.getRateLimit().setRefillTokens(1);
        properties.getRateLimit().setRefillPeriodSeconds(3600);
        return properties;
    }
}
