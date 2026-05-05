package mcp.server.zap.core.service;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Expert MCP adapter for authenticated scanning setup and diagnostics.
 */
@Service
public class ExpertAuthMcpToolsService implements ExpertToolGroup {
    private final ContextUserService contextUserService;

    public ExpertAuthMcpToolsService(ContextUserService contextUserService) {
        this.contextUserService = contextUserService;
    }

    @Tool(
            name = "zap_contexts_list",
            description = "List all ZAP contexts with context ID, scope state, and include/exclude regexes"
    )
    public List<Map<String, Object>> listContexts() {
        return contextUserService.listContexts();
    }

    @Tool(
            name = "zap_context_upsert",
            description = "Create or update a ZAP context with include/exclude regexes and optional in-scope flag"
    )
    public Map<String, Object> upsertContext(
            @ToolParam(description = "Context name (e.g. shop-auth)") String contextName,
            @ToolParam(description = "List of include URL regexes (max 20)") List<String> includeRegexes,
            @ToolParam(description = "List of exclude URL regexes (max 20)") List<String> excludeRegexes,
            @ToolParam(required = false, description = "Set context in scope (true/false). Optional.") Boolean inScope
    ) {
        return contextUserService.upsertContext(contextName, includeRegexes, excludeRegexes, inScope);
    }

    @Tool(
            name = "zap_users_list",
            description = "List users for a ZAP context ID"
    )
    public List<Map<String, Object>> listUsers(@ToolParam(description = "ZAP context ID") String contextId) {
        return contextUserService.listUsers(contextId);
    }

    @Tool(
            name = "zap_user_upsert",
            description = "Create or update a user in a ZAP context and optionally set credentials/enabled state"
    )
    public Map<String, Object> upsertUser(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "User name") String userName,
            @ToolParam(required = false, description = "Authentication credentials config params string. Optional.") String authCredentialsConfigParams,
            @ToolParam(required = false, description = "Set user enabled state (true/false). Optional.") Boolean enabled
    ) {
        return contextUserService.upsertUser(contextId, userName, authCredentialsConfigParams, enabled);
    }

    @Tool(
            name = "zap_context_auth_configure",
            description = "Configure authentication method and login indicators for a ZAP context"
    )
    public Map<String, Object> configureContextAuthentication(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "Authentication method name") String authMethodName,
            @ToolParam(required = false, description = "Authentication method config params string. Optional.") String authMethodConfigParams,
            @ToolParam(required = false, description = "Logged-in indicator regex. Optional.") String loggedInIndicatorRegex,
            @ToolParam(required = false, description = "Logged-out indicator regex. Optional.") String loggedOutIndicatorRegex
    ) {
        return contextUserService.configureContextAuthentication(
                contextId, authMethodName, authMethodConfigParams, loggedInIndicatorRegex, loggedOutIndicatorRegex);
    }

    @Tool(
            name = "zap_auth_test_user",
            description = "Test authentication as a specific user and return authentication diagnostics"
    )
    public Map<String, Object> testUserAuthentication(
            @ToolParam(description = "ZAP context ID") String contextId,
            @ToolParam(description = "ZAP user ID") String userId
    ) {
        return contextUserService.testUserAuthentication(contextId, userId);
    }
}
