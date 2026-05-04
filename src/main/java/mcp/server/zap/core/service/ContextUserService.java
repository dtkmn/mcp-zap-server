package mcp.server.zap.core.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.gateway.EngineContextAccess;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationConfigRequest;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationConfigResult;
import mcp.server.zap.core.gateway.EngineContextAccess.AuthenticationDiagnostics;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextMutationResult;
import mcp.server.zap.core.gateway.EngineContextAccess.ContextSnapshot;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutation;
import mcp.server.zap.core.gateway.EngineContextAccess.UserMutationResult;
import mcp.server.zap.core.gateway.EngineContextAccess.UserSnapshot;
import org.springframework.stereotype.Service;

/**
 * MCP tools for ZAP Context and User management.
 */
@Slf4j
@Service
public class ContextUserService {

    private static final int MAX_REGEX_ENTRIES = 20;
    private static final int MAX_REGEX_LENGTH = 512;

    private final EngineContextAccess engineContextAccess;

    /**
     * Build-time dependency injection constructor.
     */
    public ContextUserService(EngineContextAccess engineContextAccess) {
        this.engineContextAccess = engineContextAccess;
    }

    public List<Map<String, Object>> listContexts() {
        return engineContextAccess.listContexts().stream()
                .map(this::contextSummary)
                .toList();
    }

    public Map<String, Object> upsertContext(
            String contextName,
            List<String> includeRegexes,
            List<String> excludeRegexes,
            Boolean inScope
    ) {
        String normalizedContextName = requireText(contextName, "contextName");
        List<String> normalizedIncludes = validateRegexEntries(includeRegexes, "includeRegexes");
        List<String> normalizedExcludes = validateRegexEntries(excludeRegexes, "excludeRegexes");

        ContextMutationResult result = engineContextAccess.upsertContext(new ContextMutation(
                normalizedContextName,
                normalizedIncludes,
                normalizedExcludes,
                inScope
        ));
        Map<String, Object> summary = contextSummary(result.context());
        summary.put("created", result.created());
        return summary;
    }

    public List<Map<String, Object>> listUsers(
            String contextId
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        return engineContextAccess.listUsers(normalizedContextId).stream()
                .map(this::userSummary)
                .toList();
    }

    public Map<String, Object> upsertUser(
            String contextId,
            String userName,
            String authCredentialsConfigParams,
            Boolean enabled
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserName = requireText(userName, "userName");

        UserMutationResult result = engineContextAccess.upsertUser(new UserMutation(
                normalizedContextId,
                normalizedUserName,
                hasText(authCredentialsConfigParams) ? authCredentialsConfigParams.trim() : null,
                enabled
        ));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contextId", result.contextId());
        summary.put("userId", result.userId());
        summary.put("userName", hasText(result.userName()) ? result.userName() : normalizedUserName);
        summary.put("enabled", result.enabled());
        summary.put("created", result.created());
        return summary;
    }

    public Map<String, Object> configureContextAuthentication(
            String contextId,
            String authMethodName,
            String authMethodConfigParams,
            String loggedInIndicatorRegex,
            String loggedOutIndicatorRegex
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedAuthMethodName = requireText(authMethodName, "authMethodName");
        String normalizedAuthConfig = hasText(authMethodConfigParams) ? authMethodConfigParams.trim() : null;
        String normalizedLoggedInIndicator = validateOptionalRegex(loggedInIndicatorRegex, "loggedInIndicatorRegex");
        String normalizedLoggedOutIndicator = validateOptionalRegex(loggedOutIndicatorRegex, "loggedOutIndicatorRegex");

        AuthenticationConfigResult result = engineContextAccess.configureContextAuthentication(
                new AuthenticationConfigRequest(
                        normalizedContextId,
                        normalizedAuthMethodName,
                        normalizedAuthConfig,
                        normalizedLoggedInIndicator,
                        normalizedLoggedOutIndicator
                ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contextId", result.contextId());
        response.put("authMethodName", result.authMethodName());
        response.put("authMethodConfigParamsProvided", result.authMethodConfigParamsProvided());
        response.put("loggedInIndicatorSet", result.loggedInIndicatorSet());
        response.put("loggedOutIndicatorSet", result.loggedOutIndicatorSet());
        return response;
    }

    public Map<String, Object> testUserAuthentication(
            String contextId,
            String userId
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");

        AuthenticationDiagnostics diagnostics =
                engineContextAccess.testUserAuthentication(normalizedContextId, normalizedUserId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contextId", diagnostics.contextId());
        result.put("userId", diagnostics.userId());
        result.put("likelyAuthenticated", diagnostics.likelyAuthenticated());
        result.put("authResponse", diagnostics.authResponse());
        result.put("authState", diagnostics.authState());
        return result;
    }

    private Map<String, Object> contextSummary(ContextSnapshot context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contextName", context.contextName());
        summary.put("contextId", context.contextId());
        summary.put("inScope", context.inScope());
        summary.put("includeRegexes", context.includeRegexes());
        summary.put("excludeRegexes", context.excludeRegexes());
        return summary;
    }

    private Map<String, Object> userSummary(UserSnapshot user) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contextId", user.contextId());
        summary.put("userId", user.userId());
        summary.put("userName", user.userName());
        summary.put("enabled", user.enabled());
        return summary;
    }

    /**
     * Validate regex list inputs and enforce project guardrails.
     */
    private List<String> validateRegexEntries(List<String> regexes, String paramName) {
        if (regexes == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String rawRegex : regexes) {
            if (!hasText(rawRegex)) {
                continue;
            }
            String regex = rawRegex.trim();

            if (regex.length() > MAX_REGEX_LENGTH) {
                throw new IllegalArgumentException(paramName + " entry exceeds max length of " + MAX_REGEX_LENGTH);
            }

            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(paramName + " contains invalid regex: " + regex, e);
            }

            normalized.add(regex);
        }

        if (normalized.size() > MAX_REGEX_ENTRIES) {
            throw new IllegalArgumentException(paramName + " supports up to " + MAX_REGEX_ENTRIES + " entries");
        }

        return normalized;
    }

    /**
     * Validate optional regex input and return normalized value.
     */
    private String validateOptionalRegex(String regexValue, String fieldName) {
        if (!hasText(regexValue)) {
            return null;
        }
        String normalized = regexValue.trim();
        if (normalized.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds max length of " + MAX_REGEX_LENGTH);
        }
        try {
            Pattern.compile(normalized);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(fieldName + " contains invalid regex: " + normalized, e);
        }
        return normalized;
    }

    /**
     * Require a non-empty string and return trimmed value.
     */
    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    /**
     * Return true when value contains non-whitespace text.
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
