package mcp.server.zap.core.service.authz;

import mcp.server.zap.core.configuration.ToolAuthorizationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Evaluates per-tool authorization decisions from the caller's granted scopes.
 */
@Service
public class ToolAuthorizationService {
    private final ToolScopeRegistry toolScopeRegistry;
    private final ToolAuthorizationProperties properties;

    public ToolAuthorizationService(ToolScopeRegistry toolScopeRegistry,
                                    ToolAuthorizationProperties properties) {
        this.toolScopeRegistry = toolScopeRegistry;
        this.properties = properties;
    }

    public ToolAuthorizationDecision authorizeToolCall(Collection<String> grantedScopes, String toolName) {
        List<String> requiredScopes = toolScopeRegistry.getRequiredScopes(toolName);
        return evaluate(toolName, grantedScopes, requiredScopes);
    }

    public ToolAuthorizationDecision authorizeToolsList(Collection<String> grantedScopes) {
        return evaluate(ToolScopeRegistry.TOOLS_LIST_ACTION, grantedScopes, toolScopeRegistry.getToolsListRequiredScopes());
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

    private ToolAuthorizationDecision evaluate(String actionName,
                                               Collection<String> grantedScopes,
                                               List<String> requiredScopes) {
        List<String> normalizedGrantedScopes = normalizeScopes(grantedScopes);
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            return new ToolAuthorizationDecision(
                    false,
                    false,
                    actionName,
                    List.of(),
                    normalizedGrantedScopes,
                    List.of()
            );
        }

        if (isDisabled()) {
            return new ToolAuthorizationDecision(true, true, actionName, requiredScopes, normalizedGrantedScopes, List.of());
        }

        Set<String> grantedScopeSet = new LinkedHashSet<>(normalizedGrantedScopes);
        boolean hasWildcard = allowWildcard() && grantedScopeSet.contains("*");
        List<String> missingScopes = new ArrayList<>();
        for (String requiredScope : requiredScopes) {
            if (!hasWildcard && !grantedScopeSet.contains(requiredScope)) {
                missingScopes.add(requiredScope);
            }
        }
        return new ToolAuthorizationDecision(
                missingScopes.isEmpty(),
                true,
                actionName,
                requiredScopes,
                normalizedGrantedScopes,
                missingScopes
        );
    }

    public List<String> normalizeScopes(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        return scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(scope -> scope.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
