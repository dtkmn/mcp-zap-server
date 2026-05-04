package mcp.server.zap.core.service;

import mcp.server.zap.core.service.auth.bootstrap.GuidedAuthSessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Guided MCP facade for auth session bootstrap and validation.
 */
@Service
public class GuidedAuthSessionMcpToolsService {
    private final GuidedAuthSessionService guidedAuthSessionService;

    public GuidedAuthSessionMcpToolsService(GuidedAuthSessionService guidedAuthSessionService) {
        this.guidedAuthSessionService = guidedAuthSessionService;
    }

    @Tool(
            name = "zap_auth_session_prepare",
            description = "Prepare a guided auth session for simple form-login, bearer, or api-key flows. Prefer credentialReference values like env:NAME or file:/absolute/path. Inline secrets are disabled by default and should remain local-only."
    )
    public String prepareAuthSession(
            @ToolParam(description = "Target host or base URL for the authenticated flow") String targetUrl,
            @ToolParam(description = "Auth kind: form, bearer, or api-key") String authKind,
            @ToolParam(description = "Preferred secret reference such as env:SCAN_PASSWORD or file:/var/run/secrets/scan-password") String credentialReference,
            @ToolParam(required = false, description = "Optional inline secret. Disabled by default; only use for local self-serve workflows when explicitly enabled.") String inlineSecret,
            @ToolParam(required = false, description = "Optional session label used to name the prepared session") String sessionLabel,
            @ToolParam(required = false, description = "Optional context name for form auth flows") String contextName,
            @ToolParam(required = false, description = "Optional login URL for form auth flows") String loginUrl,
            @ToolParam(required = false, description = "Optional login username for form auth flows") String username,
            @ToolParam(required = false, description = "Optional scan user name for form auth flows") String userName,
            @ToolParam(required = false, description = "Optional username field name for form auth flows (default: username)") String usernameField,
            @ToolParam(required = false, description = "Optional password field name for form auth flows (default: password)") String passwordField,
            @ToolParam(required = false, description = "Optional header name for bearer or api-key auth (default: Authorization for bearer, X-API-Key for api-key)") String headerName,
            @ToolParam(required = false, description = "Optional logged-in indicator regex for form auth flows") String loggedInIndicatorRegex,
            @ToolParam(required = false, description = "Optional logged-out indicator regex for form auth flows") String loggedOutIndicatorRegex
    ) {
        return guidedAuthSessionService.prepareSession(
                targetUrl,
                authKind,
                credentialReference,
                inlineSecret,
                sessionLabel,
                contextName,
                loginUrl,
                username,
                userName,
                usernameField,
                passwordField,
                headerName,
                loggedInIndicatorRegex,
                loggedOutIndicatorRegex
        );
    }

    @Tool(
            name = "zap_auth_session_validate",
            description = "Validate a prepared guided auth session before authenticated crawl or attack flows."
    )
    public String validateAuthSession(
            @ToolParam(description = "Session ID returned by zap_auth_session_prepare") String sessionId
    ) {
        return guidedAuthSessionService.validateSession(sessionId);
    }
}
