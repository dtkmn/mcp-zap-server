package mcp.server.zap.core.service.authz;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import mcp.gateway.core.authz.McpToolAuthorizer;
import mcp.gateway.core.authz.ToolAuthorizationDecision;
import mcp.gateway.core.authz.ToolAuthorizationRequest;
import mcp.gateway.core.context.GatewayToolExecutionContext;
import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import org.springframework.stereotype.Service;

/**
 * Evaluates per-tool authorization decisions from the caller's granted scopes.
 */
@Service
public class ToolAuthorizationService {
    private final ToolScopeRegistry toolScopeRegistry;
    private final ToolAuthorizationProperties properties;
    private final McpToolAuthorizer authorizer;

    public ToolAuthorizationService(ToolScopeRegistry toolScopeRegistry,
                                    ToolAuthorizationProperties properties) {
        this.toolScopeRegistry = toolScopeRegistry;
        this.properties = properties;
        this.authorizer = McpToolAuthorizer.of(
                toolScopeRegistry.getToolAccessRegistry(),
                toolScopeRegistry.getToolsListRequiredScopes()
        );
    }

    public ToolAuthorizationDecision authorizeToolCall(Collection<String> grantedScopes, String toolName) {
        return authorizer.authorizeToolCall(toolName, grantedScopes, allowWildcard(), !isDisabled());
    }

    public ToolAuthorizationDecision authorizeToolsList(Collection<String> grantedScopes) {
        return authorizer.authorizeToolsList(grantedScopes, allowWildcard(), !isDisabled());
    }

    public ToolAuthorizationDecision authorize(Collection<String> grantedScopes,
                                               GatewayToolExecutionContext context) {
        return authorizer.authorize(context, grantedScopes, allowWildcard(), !isDisabled());
    }

    public boolean isEnforced() {
        return properties.getMode() == ToolAuthorizationProperties.Mode.ENFORCE;
    }

    public boolean isWarnOnly() {
        return properties.getMode() == ToolAuthorizationProperties.Mode.WARN;
    }

    public boolean isDisabled() {
        return properties.getMode() == ToolAuthorizationProperties.Mode.OFF;
    }

    public boolean allowWildcard() {
        return properties.isAllowWildcard();
    }

    public Set<String> mappedToolNames() {
        return toolScopeRegistry.getRequiredScopesByTool().keySet();
    }

    public List<String> normalizeScopes(Collection<String> scopes) {
        return ToolAuthorizationRequest.normalizeScopes(scopes);
    }
}
