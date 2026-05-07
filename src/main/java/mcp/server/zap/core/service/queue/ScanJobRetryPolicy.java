package mcp.server.zap.core.service.queue;

public class ScanJobRetryPolicy {
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double multiplier;

    public ScanJobRetryPolicy(int maxAttempts, long initialBackoffMs, long maxBackoffMs, double multiplier) {
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.multiplier = multiplier;
    }

    public ScanJobRetryPolicy sanitized() {
        int sanitizedMaxAttempts = Math.max(1, maxAttempts);
        long sanitizedInitial = Math.max(0, initialBackoffMs);
        long sanitizedMax = Math.max(sanitizedInitial, maxBackoffMs);
        double sanitizedMultiplier = multiplier < 1.0 ? 1.0 : multiplier;
        return new ScanJobRetryPolicy(sanitizedMaxAttempts, sanitizedInitial, sanitizedMax, sanitizedMultiplier);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long computeDelayMs(int attemptsCompleted) {
        if (maxBackoffMs == 0 || initialBackoffMs == 0) {
            return 0;
        }
        int exponent = Math.max(0, attemptsCompleted - 1);
        double raw = initialBackoffMs * Math.pow(multiplier, exponent);
        long bounded = Math.min(maxBackoffMs, Math.round(raw));
        return Math.max(0, bounded);
    }

    @Override
    public String toString() {
        return "RetryPolicy{" +
                "maxAttempts=" + maxAttempts +
                ", initialBackoffMs=" + initialBackoffMs +
                ", maxBackoffMs=" + maxBackoffMs +
                ", multiplier=" + multiplier +
                '}';
    }
}
