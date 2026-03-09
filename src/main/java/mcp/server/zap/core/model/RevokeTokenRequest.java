package mcp.server.zap.core.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request object for token revocation.
 */
@Data
public class RevokeTokenRequest {
    @NotBlank(message = "Token is required")
    private String token;
}
