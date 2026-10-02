package mcp.server.zap.core.service.revocation;

/**
 * The authoritative revocation operation could not be completed.
 */
public final class TokenRevocationUnavailableException extends IllegalStateException {
    public TokenRevocationUnavailableException(Throwable cause) {
        super("Token revocation service is temporarily unavailable.", cause);
    }
}
