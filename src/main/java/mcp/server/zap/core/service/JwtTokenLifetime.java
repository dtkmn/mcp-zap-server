package mcp.server.zap.core.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared timestamp policy for JWT validation and revocation retention.
 */
public final class JwtTokenLifetime {

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private JwtTokenLifetime() {}

    /**
     * Raw expiry claims at or after this cutoff can still be accepted.
     * Stored expiry claims remain unchanged, including records written before this policy.
     */
    public static Instant revocationCutoff(Instant now) {
        return now.minus(CLOCK_SKEW);
    }
}
