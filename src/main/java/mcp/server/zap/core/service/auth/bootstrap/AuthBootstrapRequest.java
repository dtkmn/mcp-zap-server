package mcp.server.zap.core.service.auth.bootstrap;

/**
 * Gateway request model for guided auth bootstrap preparation.
 */
public record AuthBootstrapRequest(
        String profileId,
        String targetUrl,
        AuthBootstrapKind authKind,
        HttpOrigin allowedOrigin,
        String credentialReference,
        String loginUrl,
        String username,
        String zapUserName,
        String usernameField,
        String passwordField,
        String headerName,
        String loggedInIndicatorRegex,
        String loggedOutIndicatorRegex
) {
}
