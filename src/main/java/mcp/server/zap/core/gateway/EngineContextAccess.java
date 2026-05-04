package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing access contract for engine context, user, and auth state.
 */
public interface EngineContextAccess {

    List<ContextSnapshot> listContexts();

    ContextMutationResult upsertContext(ContextMutation mutation);

    List<UserSnapshot> listUsers(String contextId);

    UserMutationResult upsertUser(UserMutation mutation);

    AuthenticationConfigResult configureContextAuthentication(AuthenticationConfigRequest request);

    AuthenticationDiagnostics testUserAuthentication(String contextId, String userId);

    record ContextMutation(
            String contextName,
            List<String> includeRegexes,
            List<String> excludeRegexes,
            Boolean inScope
    ) {
        public ContextMutation {
            includeRegexes = includeRegexes == null ? List.of() : List.copyOf(includeRegexes);
            excludeRegexes = excludeRegexes == null ? List.of() : List.copyOf(excludeRegexes);
        }
    }

    record ContextSnapshot(
            String contextName,
            String contextId,
            Boolean inScope,
            List<String> includeRegexes,
            List<String> excludeRegexes
    ) {
        public ContextSnapshot {
            includeRegexes = includeRegexes == null ? List.of() : List.copyOf(includeRegexes);
            excludeRegexes = excludeRegexes == null ? List.of() : List.copyOf(excludeRegexes);
        }
    }

    record ContextMutationResult(
            ContextSnapshot context,
            boolean created
    ) {
    }

    record UserSnapshot(
            String contextId,
            String userId,
            String userName,
            Boolean enabled
    ) {
    }

    record UserMutation(
            String contextId,
            String userName,
            String authCredentialsConfigParams,
            Boolean enabled
    ) {
    }

    record UserMutationResult(
            String contextId,
            String userId,
            String userName,
            Boolean enabled,
            boolean created
    ) {
    }

    record AuthenticationConfigRequest(
            String contextId,
            String authMethodName,
            String authMethodConfigParams,
            String loggedInIndicatorRegex,
            String loggedOutIndicatorRegex
    ) {
    }

    record AuthenticationConfigResult(
            String contextId,
            String authMethodName,
            boolean authMethodConfigParamsProvided,
            boolean loggedInIndicatorSet,
            boolean loggedOutIndicatorSet
    ) {
    }

    record AuthenticationDiagnostics(
            String contextId,
            String userId,
            Boolean likelyAuthenticated,
            String authResponse,
            String authState
    ) {
    }
}
