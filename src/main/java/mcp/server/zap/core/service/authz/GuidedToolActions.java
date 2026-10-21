package mcp.server.zap.core.service.authz;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Keeps guided follow-up suggestions consistent with the caller's effective tool permissions.
 * This is presentation guidance; the request filter remains the enforcement boundary.
 */
@Component
public class GuidedToolActions {
    private static final String SCOPE_PREFIX = "SCOPE_";

    private final ToolAuthorizationService authorizationService;
    private final boolean securityEnabled;
    private final String securityMode;

    public GuidedToolActions(ToolAuthorizationService authorizationService,
                             @Value("${mcp.server.security.enabled:true}") boolean securityEnabled,
                             @Value("${mcp.server.security.mode:api-key}") String securityMode) {
        this.authorizationService = authorizationService;
        this.securityEnabled = securityEnabled;
        this.securityMode = securityMode;
    }

    public boolean canCall(String toolName) {
        if (!securityEnabled || (securityMode != null && "none".equalsIgnoreCase(securityMode.trim()))
                || !authorizationService.isEnforced()) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<String> scopes = authentication == null ? List.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(SCOPE_PREFIX))
                .map(authority -> authority.substring(SCOPE_PREFIX.length()))
                .toList();
        return authorizationService.authorizeToolCall(scopes, toolName).allowed();
    }
}
