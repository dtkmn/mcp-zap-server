package mcp.server.zap.model;

import lombok.Data;

/**
 * Request object for token refresh.
 */
@Data
public class RefreshTokenRequest {
    private String refreshToken;
}
