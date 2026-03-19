package mcp.server.zap.core.service.protection;

import mcp.server.zap.core.configuration.ApiKeyProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves client and workspace identity from authentication plus configured client metadata.
 */
@Service
public class ClientWorkspaceResolver {
    private static final String DEFAULT_CLIENT_ID = "anonymous";
    private static final String DEFAULT_WORKSPACE_ID = "default-workspace";

    private final ApiKeyProperties apiKeyProperties;

    public ClientWorkspaceResolver(ApiKeyProperties apiKeyProperties) {
        this.apiKeyProperties = apiKeyProperties;
    }

    public String resolveCurrentClientId() {
        if (hasText(RequestIdentityHolder.currentClientId())) {
            return RequestIdentityHolder.currentClientId().trim();
        }
        return resolveClientId(SecurityContextHolder.getContext().getAuthentication());
    }

    public String resolveCurrentWorkspaceId() {
        if (hasText(RequestIdentityHolder.currentWorkspaceId())) {
            return RequestIdentityHolder.currentWorkspaceId().trim();
        }
        return resolveWorkspaceId(resolveCurrentClientId());
    }

    public String resolveClientId(Authentication authentication) {
        if (authentication == null || !hasText(authentication.getName())) {
            return DEFAULT_CLIENT_ID;
        }
        return authentication.getName().trim();
    }

    public String resolveWorkspaceId(String clientId) {
        if (!hasText(clientId)) {
            return DEFAULT_WORKSPACE_ID;
        }

        for (ApiKeyProperties.ApiKeyClient client : apiKeyProperties.getApiKeys()) {
            if (client != null && clientId.equals(client.getClientId())) {
                if (hasText(client.getWorkspaceId())) {
                    return client.getWorkspaceId().trim();
                }
                return clientId.trim();
            }
        }

        return DEFAULT_CLIENT_ID.equals(clientId) ? DEFAULT_WORKSPACE_ID : clientId.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
