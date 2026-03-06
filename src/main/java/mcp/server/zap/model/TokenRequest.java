package mcp.server.zap.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request object for token generation.
 */
@Data
public class TokenRequest {
    @NotBlank(message = "API key is required")
    private String apiKey;
}
