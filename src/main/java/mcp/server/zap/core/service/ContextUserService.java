package mcp.server.zap.core.service;

import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MCP tools for ZAP Context and User management.
 */
@Slf4j
@Service
public class ContextUserService {

    private static final int MAX_REGEX_ENTRIES = 20;
    private static final int MAX_REGEX_LENGTH = 512;

    private final ClientApi zap;

    /**
     * Build-time dependency injection constructor.
     */
    public ContextUserService(ClientApi zap) {
        this.zap = zap;
    }

    public List<Map<String, Object>> listContexts() {
        try {
            List<String> names = listContextNames();
            List<Map<String, Object>> contexts = new ArrayList<>();

            for (String contextName : names) {
                contexts.add(buildContextSummary(contextName));
            }

            return contexts;
        } catch (ClientApiException e) {
            log.error("Failed to list contexts: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to list contexts", e);
        }
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

        try {
            boolean created = false;
            List<String> existingNames = listContextNames();
            if (!existingNames.contains(normalizedContextName)) {
                zap.context.newContext(normalizedContextName);
                created = true;
            }

            zap.context.setContextRegexs(
                    normalizedContextName,
                    toJsonArray(normalizedIncludes),
                    toJsonArray(normalizedExcludes)
            );

            if (inScope != null) {
                zap.context.setContextInScope(normalizedContextName, Boolean.toString(inScope));
            }

            Map<String, Object> summary = buildContextSummary(normalizedContextName);
            summary.put("created", created);
            return summary;
        } catch (ClientApiException e) {
            log.error("Failed to upsert context '{}': {}", normalizedContextName, e.getMessage(), e);
            throw new ZapApiException("Failed to upsert context " + normalizedContextName, e);
        }
    }

    public List<Map<String, Object>> listUsers(
            String contextId
    ) {
        String normalizedContextId = requireText(contextId, "contextId");

        try {
            ApiResponse response = zap.users.usersList(normalizedContextId);
            if (!(response instanceof ApiResponseList list)) {
                return List.of();
            }

            List<Map<String, Object>> users = new ArrayList<>();
            for (ApiResponse item : list.getItems()) {
                UserRecord user = toUserRecord(item);
                if (user == null) {
                    continue;
                }

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("contextId", normalizedContextId);
                summary.put("userId", user.userId());
                summary.put("userName", user.userName());
                summary.put("enabled", user.enabled());
                users.add(summary);
            }
            return users;
        } catch (ClientApiException e) {
            log.error("Failed to list users for context {}: {}", normalizedContextId, e.getMessage(), e);
            throw new ZapApiException("Failed to list users for context " + normalizedContextId, e);
        }
    }

    public Map<String, Object> upsertUser(
            String contextId,
            String userName,
            String authCredentialsConfigParams,
            Boolean enabled
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserName = requireText(userName, "userName");

        try {
            UserRecord existing = findUserByName(normalizedContextId, normalizedUserName);
            boolean created = false;
            String userId;

            if (existing == null) {
                ApiResponse createResponse = zap.users.newUser(normalizedContextId, normalizedUserName);
                userId = extractFirstValue(createResponse, "userId", "id");
                if (userId == null || userId.isBlank()) {
                    UserRecord fromList = findUserByName(normalizedContextId, normalizedUserName);
                    if (fromList == null || fromList.userId() == null || fromList.userId().isBlank()) {
                        String message = "Unable to resolve user ID for newly created user: " + normalizedUserName;
                        throw new ZapApiException(message, new IllegalStateException(message));
                    }
                    userId = fromList.userId();
                }
                created = true;
            } else {
                userId = existing.userId();
                if (userId == null || userId.isBlank()) {
                    String message = "Found existing user without a userId: " + normalizedUserName;
                    throw new ZapApiException(message, new IllegalStateException(message));
                }
            }

            if (hasText(authCredentialsConfigParams)) {
                zap.users.setAuthenticationCredentials(normalizedContextId, userId, authCredentialsConfigParams.trim());
            }

            if (enabled != null) {
                zap.users.setUserEnabled(normalizedContextId, userId, Boolean.toString(enabled));
            }

            ApiResponse userResponse = zap.users.getUserById(normalizedContextId, userId);
            UserRecord latest = toUserRecord(userResponse);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("contextId", normalizedContextId);
            summary.put("userId", userId);
            summary.put("userName", latest != null && hasText(latest.userName()) ? latest.userName() : normalizedUserName);
            summary.put("enabled", latest != null ? latest.enabled() : enabled);
            summary.put("created", created);
            return summary;
        } catch (ClientApiException e) {
            log.error("Failed to upsert user '{}' for context {}: {}", normalizedUserName, normalizedContextId, e.getMessage(), e);
            throw new ZapApiException("Failed to upsert user " + normalizedUserName, e);
        }
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

        try {
            zap.authentication.setAuthenticationMethod(
                    normalizedContextId,
                    normalizedAuthMethodName,
                    normalizedAuthConfig
            );

            if (normalizedLoggedInIndicator != null) {
                zap.authentication.setLoggedInIndicator(normalizedContextId, normalizedLoggedInIndicator);
            }
            if (normalizedLoggedOutIndicator != null) {
                zap.authentication.setLoggedOutIndicator(normalizedContextId, normalizedLoggedOutIndicator);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contextId", normalizedContextId);
            result.put("authMethodName", normalizedAuthMethodName);
            result.put("authMethodConfigParamsProvided", normalizedAuthConfig != null);
            result.put("loggedInIndicatorSet", normalizedLoggedInIndicator != null);
            result.put("loggedOutIndicatorSet", normalizedLoggedOutIndicator != null);
            return result;
        } catch (ClientApiException e) {
            log.error("Failed to configure authentication for context {}: {}", normalizedContextId, e.getMessage(), e);
            throw new ZapApiException("Failed to configure authentication for context " + normalizedContextId, e);
        }
    }

    public Map<String, Object> testUserAuthentication(
            String contextId,
            String userId
    ) {
        String normalizedContextId = requireText(contextId, "contextId");
        String normalizedUserId = requireText(userId, "userId");

        try {
            ApiResponse authResponse = zap.users.authenticateAsUser(normalizedContextId, normalizedUserId);
            ApiResponse authStateResponse = zap.users.getAuthenticationState(normalizedContextId, normalizedUserId);

            Boolean likelyAuthenticated = parseBoolean(
                    extractFirstValue(authStateResponse, "lastPollResult", "authenticated", "loggedIn")
            );
            if (likelyAuthenticated == null) {
                likelyAuthenticated = parseBoolean(
                        extractFirstValue(authResponse, "authSuccessful", "success", "result")
                );
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("contextId", normalizedContextId);
            result.put("userId", normalizedUserId);
            result.put("likelyAuthenticated", likelyAuthenticated);
            result.put("authResponse", authResponse.toString(0));
            result.put("authState", authStateResponse.toString(0));
            return result;
        } catch (ClientApiException e) {
            log.error("Failed to authenticate as user {} in context {}: {}", normalizedUserId, normalizedContextId, e.getMessage(), e);
            throw new ZapApiException("Failed to authenticate as user " + normalizedUserId, e);
        }
    }

    private List<String> listContextNames() throws ClientApiException {
        ApiResponse response = zap.context.contextList();
        return extractStringList(response).stream().filter(this::hasText).toList();
    }

    private Map<String, Object> buildContextSummary(String contextName) throws ClientApiException {
        ApiResponse contextResponse = zap.context.context(contextName);
        String contextId = extractFirstValue(contextResponse, "id", "contextId");
        Boolean inScope = parseBoolean(extractFirstValue(contextResponse, "inScope", "isInScope", "booleanInScope"));

        List<String> includes = extractStringList(zap.context.includeRegexs(contextName));
        List<String> excludes = extractStringList(zap.context.excludeRegexs(contextName));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contextName", contextName);
        summary.put("contextId", contextId);
        summary.put("inScope", inScope);
        summary.put("includeRegexes", includes);
        summary.put("excludeRegexes", excludes);
        return summary;
    }

    private UserRecord findUserByName(String contextId, String userName) throws ClientApiException {
        ApiResponse response = zap.users.usersList(contextId);
        if (!(response instanceof ApiResponseList list)) {
            return null;
        }

        for (ApiResponse item : list.getItems()) {
            UserRecord user = toUserRecord(item);
            if (user != null && hasText(user.userName()) && user.userName().equals(userName)) {
                return user;
            }
        }
        return null;
    }

    /**
     * Parse user structure returned by ZAP API into normalized record.
     */
    private UserRecord toUserRecord(ApiResponse response) {
        if (!(response instanceof ApiResponseSet set)) {
            return null;
        }

        String userId = extractFirstValue(set, "id", "userId");
        String name = extractFirstValue(set, "name", "userName");
        Boolean enabled = parseBoolean(extractFirstValue(set, "enabled", "isEnabled"));

        return new UserRecord(userId, name, enabled);
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

    /**
     * Extract string values from heterogeneous ZAP API response shapes.
     */
    private List<String> extractStringList(ApiResponse response) {
        List<String> values = new ArrayList<>();
        switch (response) {
            case null -> {
                return values;
            }
            case ApiResponseElement element -> {
                if (hasText(element.getValue())) {
                    values.add(element.getValue().trim());
                }
                return values;
            }
            case ApiResponseList list -> {
                for (ApiResponse item : list.getItems()) {
                    values.addAll(extractStringList(item));
                }
                return values;
            }
            case ApiResponseSet set -> {
                for (ApiResponse item : set.getValues()) {
                    values.addAll(extractStringList(item));
                }
            }
            default -> {
            }
        }

        return values;
    }

    /**
     * Extract first non-blank value from candidate keys in response payload.
     */
    private String extractFirstValue(ApiResponse response, String... candidateKeys) {
        if (response instanceof ApiResponseElement element) {
            return normalizeOptionalValue(element.getValue());
        }

        if (response instanceof ApiResponseSet set) {
            List<String> orderedKeys = Arrays.stream(candidateKeys).filter(Objects::nonNull).toList();
            for (String key : orderedKeys) {
                String value = normalizeOptionalValue(set.getStringValue(key));
                if (value != null) {
                    return value;
                }
            }

            for (ApiResponse nested : set.getValues()) {
                String nestedValue = extractFirstValue(nested, candidateKeys);
                if (nestedValue != null) {
                    return nestedValue;
                }
            }
        }

        if (response instanceof ApiResponseList list) {
            for (ApiResponse item : list.getItems()) {
                String value = extractFirstValue(item, candidateKeys);
                if (value != null) {
                    return value;
                }
            }
        }

        return null;
    }

    /**
     * Normalize optional values by trimming blanks to null.
     */
    private String normalizeOptionalValue(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * Parse optional boolean value from text representation.
     */
    private Boolean parseBoolean(String value) {
        if (!hasText(value)) {
            return null;
        }

        String normalized = value.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Serialize list values into compact JSON array literal.
     */
    private String toJsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(values.get(i))).append('"');
        }
        builder.append(']');
        return builder.toString();
    }

    /**
     * Escape JSON-special characters in raw user-provided text values.
     */
    private String escapeJson(String input) {
        StringBuilder builder = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private record UserRecord(String userId, String userName, Boolean enabled) {
    }
}
