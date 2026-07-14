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
            description = "Prepare a guided auth session from an operator-managed profile. The requested target must remain on the profile's authorized origin."
    )
    public String prepareAuthSession(
            @ToolParam(description = "Operator-configured authentication profile ID") String profileId,
            @ToolParam(description = "Target URL on the profile's authorized origin; paths may vary") String targetUrl
    ) {
        return guidedAuthSessionService.prepareSession(profileId, targetUrl);
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
