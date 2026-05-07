package mcp.server.zap.core.service.protection;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mcp.server.zap.core.configuration.AuthRateLimitProperties;
import org.springframework.stereotype.Service;

/**
 * In-memory token-bucket limiter for unauthenticated auth exchange endpoints.
 */
@Service
public class AuthEndpointRateLimiter {
    private final AuthRateLimitProperties properties;
    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    public AuthEndpointRateLimiter(AuthRateLimitProperties properties) {
        this.properties = properties;
    }

    public boolean tryConsume(String key) {
        if (!properties.isEnabled()) {
            return true;
        }

        String normalizedKey = normalizeKey(key);
        evictIfNeeded();
        BucketState state = buckets.computeIfAbsent(normalizedKey, ignored -> new BucketState(
                Math.max(1, properties.getCapacity()),
                System.nanoTime(),
                System.currentTimeMillis()
        ));

        synchronized (state) {
            refill(state);
            state.lastAccessMillis = System.currentTimeMillis();
            if (state.tokens >= 1.0d) {
                state.tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }

    public long retryAfterSeconds(String key) {
        if (!properties.isEnabled()) {
            return 1L;
        }

        BucketState state = buckets.get(normalizeKey(key));
        if (state == null) {
            return 1L;
        }

        synchronized (state) {
            refill(state);
            if (state.tokens >= 1.0d) {
                return 1L;
            }
            long refillPeriodNanos = Math.max(1L, properties.getRefillPeriodSeconds()) * 1_000_000_000L;
            double refillTokens = Math.max(1, properties.getRefillTokens());
            double nanosPerToken = refillPeriodNanos / refillTokens;
            long nowNanos = System.nanoTime();
            double missingTokens = 1.0d - state.tokens;
            long nanosUntilToken = Math.max(1L, (long) Math.ceil(missingTokens * nanosPerToken)
                    - (nowNanos - state.lastRefillNanos));
            long seconds = (long) Math.ceil(nanosUntilToken / 1_000_000_000.0d);
            return Math.max(1L, seconds);
        }
    }

    private void refill(BucketState state) {
        long refillPeriodNanos = Math.max(1L, properties.getRefillPeriodSeconds()) * 1_000_000_000L;
        double refillTokens = Math.max(1, properties.getRefillTokens());
        long nowNanos = System.nanoTime();
        long elapsedNanos = Math.max(0L, nowNanos - state.lastRefillNanos);
        if (elapsedNanos == 0L) {
            return;
        }

        double tokensToAdd = (elapsedNanos / (double) refillPeriodNanos) * refillTokens;
        if (tokensToAdd <= 0.0d) {
            return;
        }

        state.tokens = Math.min(Math.max(1, properties.getCapacity()), state.tokens + tokensToAdd);
        state.lastRefillNanos = nowNanos;
    }

    private void evictIfNeeded() {
        int maxTrackedKeys = Math.max(100, properties.getMaxTrackedKeys());
        if (buckets.size() < maxTrackedKeys) {
            return;
        }

        long staleBefore = System.currentTimeMillis()
                - (Math.max(1L, properties.getRefillPeriodSeconds()) * 1000L * 5L);
        Iterator<Map.Entry<String, BucketState>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && buckets.size() >= maxTrackedKeys) {
            Map.Entry<String, BucketState> entry = iterator.next();
            if (entry.getValue().lastAccessMillis < staleBefore) {
                buckets.remove(entry.getKey(), entry.getValue());
            } else {
                break;
            }
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "anonymous";
        }
        return key.trim();
    }

    private static final class BucketState {
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessMillis;

        private BucketState(double tokens, long lastRefillNanos, long lastAccessMillis) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
