package mcp.server.zap.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request object for token refresh.
 */
@Data
public class RefreshTokenRequest {
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
