package mcp.server.zap.core.service.protection;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mcp.server.zap.core.configuration.AbuseProtectionProperties;
import org.springframework.stereotype.Service;

/**
 * Simple in-memory token-bucket limiter keyed by client ID.
 */
@Service
public class ClientRateLimiter {
    private final AbuseProtectionProperties properties;
    private final ConcurrentHashMap<String, BucketState> buckets = new ConcurrentHashMap<>();

    public ClientRateLimiter(AbuseProtectionProperties properties) {
        this.properties = properties;
    }

    public boolean tryConsume(String clientId) {
        if (!properties.isEnabled() || !properties.getRateLimit().isEnabled()) {
            return true;
        }

        String normalizedClientId = normalizeClientId(clientId);
        evictIfNeeded();
        BucketState state = buckets.computeIfAbsent(normalizedClientId, ignored -> new BucketState(
                properties.getRateLimit().getCapacity(),
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

    public long retryAfterSeconds(String clientId) {
        if (!properties.isEnabled() || !properties.getRateLimit().isEnabled()) {
            return Math.max(1L, properties.getRetryAfterSeconds());
        }

        BucketState state = buckets.get(normalizeClientId(clientId));
        if (state == null) {
            return 1L;
        }

        synchronized (state) {
            refill(state);
            if (state.tokens >= 1.0d) {
                return 1L;
            }
            long refillPeriodNanos = Math.max(1L, properties.getRateLimit().getRefillPeriodSeconds()) * 1_000_000_000L;
            double refillTokens = Math.max(1, properties.getRateLimit().getRefillTokens());
            double nanosPerToken = refillPeriodNanos / refillTokens;
            long nowNanos = System.nanoTime();
            double missingTokens = 1.0d - state.tokens;
            long nanosUntilToken = Math.max(1L, (long) Math.ceil(missingTokens * nanosPerToken) - (nowNanos - state.lastRefillNanos));
            long seconds = (long) Math.ceil(nanosUntilToken / 1_000_000_000.0d);
            return Math.max(1L, seconds);
        }
    }

    private void refill(BucketState state) {
        long refillPeriodNanos = Math.max(1L, properties.getRateLimit().getRefillPeriodSeconds()) * 1_000_000_000L;
        double refillTokens = Math.max(1, properties.getRateLimit().getRefillTokens());
        long nowNanos = System.nanoTime();
        long elapsedNanos = Math.max(0L, nowNanos - state.lastRefillNanos);
        if (elapsedNanos == 0L) {
            return;
        }

        double tokensToAdd = (elapsedNanos / (double) refillPeriodNanos) * refillTokens;
        if (tokensToAdd <= 0.0d) {
            return;
        }

        state.tokens = Math.min(properties.getRateLimit().getCapacity(), state.tokens + tokensToAdd);
        state.lastRefillNanos = nowNanos;
    }

    private void evictIfNeeded() {
        int maxTrackedClients = Math.max(100, properties.getRateLimit().getMaxTrackedClients());
        if (buckets.size() < maxTrackedClients) {
            return;
        }

        long staleBefore = System.currentTimeMillis() - (Math.max(1L, properties.getRateLimit().getRefillPeriodSeconds()) * 1000L * 5L);
        Iterator<Map.Entry<String, BucketState>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext() && buckets.size() >= maxTrackedClients) {
            Map.Entry<String, BucketState> entry = iterator.next();
            if (entry.getValue().lastAccessMillis < staleBefore) {
                buckets.remove(entry.getKey(), entry.getValue());
            } else {
                break;
            }
        }
    }

    private String normalizeClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return "anonymous";
        }
        return clientId.trim();
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
