package mcp.server.zap.core.service.auth.bootstrap;

/**
 * Gateway request model for guided auth bootstrap preparation.
 */
public record AuthBootstrapRequest(
        String sessionLabel,
        String targetUrl,
        AuthBootstrapKind authKind,
        String credentialReference,
        String inlineSecret,
        String contextName,
        String loginUrl,
        String username,
        String userName,
        String usernameField,
        String passwordField,
        String headerName,
        String loggedInIndicatorRegex,
        String loggedOutIndicatorRegex
) {
}
