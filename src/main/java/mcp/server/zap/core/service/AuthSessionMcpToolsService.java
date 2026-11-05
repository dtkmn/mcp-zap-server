package mcp.server.zap.core.service;

import mcp.server.zap.core.service.auth.bootstrap.AuthSessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tools to prepare and validate authentication sessions.
 */
@Service
public class AuthSessionMcpToolsService {
    private final AuthSessionService authSessionService;

    public AuthSessionMcpToolsService(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @Tool(
            name = "zap_auth_session_prepare",
            description = "Prepare an authentication session from an operator-managed profile. Use a kind=browser profile for Client Spider, or kind=form for HTTP spider and active scans. The requested target must remain on the profile's authorized origin."
    )
    public String prepareAuthSession(
            @ToolParam(description = "Operator-configured authentication profile ID") String profileId,
            @ToolParam(description = "Target URL on the profile's authorized origin; paths may vary") String targetUrl
    ) {
        return authSessionService.prepareSession(profileId, targetUrl);
    }

    @Tool(
            name = "zap_auth_session_validate",
            description = "Validate a prepared authentication session before an authenticated crawl or active scan."
    )
    public String validateAuthSession(
            @ToolParam(description = "Session ID returned by zap_auth_session_prepare") String sessionId
    ) {
        return authSessionService.validateSession(sessionId);
    }
}
