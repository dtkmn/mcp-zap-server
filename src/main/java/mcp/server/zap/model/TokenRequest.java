package mcp.server.zap.model;

import lombok.Data;

/**
 * Request object for token generation.
 */
@Data
public class TokenRequest {
    private String apiKey;
}
