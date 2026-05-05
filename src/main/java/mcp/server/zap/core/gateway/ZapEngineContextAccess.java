package mcp.server.zap.core.gateway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.exception.ZapApiException;
import org.springframework.stereotype.Component;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ApiResponseSet;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

/**
 * ZAP-backed implementation of the gateway context/user/auth access boundary.
 */
@Slf4j
@Component
public class ZapEngineContextAccess implements EngineContextAccess {

    private final ClientApi zap;

    public ZapEngineContextAccess(ClientApi zap) {
        this.zap = zap;
    }

    @Override
    public List<ContextSnapshot> listContexts() {
        try {
            List<String> names = listContextNames();
            List<ContextSnapshot> contexts = new ArrayList<>();
            for (String contextName : names) {
                contexts.add(loadContext(contextName));
            }
            return List.copyOf(contexts);
        } catch (ClientApiException e) {
            log.error("Failed to list contexts: {}", e.getMessage(), e);
            throw new ZapApiException("Failed to list contexts", e);
        }
    }

    @Override
    public ContextMutationResult upsertContext(ContextMutation mutation) {
        try {
            boolean created = false;
            if (!listContextNames().contains(mutation.contextName())) {
                zap.context.newContext(mutation.contextName());
                created = true;
            }

            zap.context.setContextRegexs(
                    mutation.contextName(),
                    toJsonArray(mutation.includeRegexes()),
                    toJsonArray(mutation.excludeRegexes())
            );

            if (mutation.inScope() != null) {
                zap.context.setContextInScope(mutation.contextName(), Boolean.toString(mutation.inScope()));
            }

            return new ContextMutationResult(loadContext(mutation.contextName()), created);
        } catch (ClientApiException e) {
            log.error("Failed to upsert context '{}': {}", mutation.contextName(), e.getMessage(), e);
            throw new ZapApiException("Failed to upsert context " + mutation.contextName(), e);
        }
    }

    @Override
    public List<UserSnapshot> listUsers(String contextId) {
        try {
            ApiResponse response = zap.users.usersList(contextId);
            if (!(response instanceof ApiResponseList list)) {
                return List.of();
            }

            List<UserSnapshot> users = new ArrayList<>();
            for (ApiResponse item : list.getItems()) {
                UserRecord user = toUserRecord(item);
                if (user != null) {
                    users.add(new UserSnapshot(contextId, user.userId(), user.userName(), user.enabled()));
                }
            }
            return List.copyOf(users);
        } catch (ClientApiException e) {
            log.error("Failed to list users for context {}: {}", contextId, e.getMessage(), e);
            throw new ZapApiException("Failed to list users for context " + contextId, e);
        }
    }

    @Override
    public UserMutationResult upsertUser(UserMutation mutation) {
        try {
            UserRecord existing = findUserByName(mutation.contextId(), mutation.userName());
            boolean created = false;
            String userId;

            if (existing == null) {
                ApiResponse createResponse = zap.users.newUser(mutation.contextId(), mutation.userName());
                userId = extractFirstValue(createResponse, "userId", "id");
                if (!hasText(userId)) {
                    UserRecord fromList = findUserByName(mutation.contextId(), mutation.userName());
                    if (fromList == null || !hasText(fromList.userId())) {
                        String message = "Unable to resolve user ID for newly created user: " + mutation.userName();
                        throw new ZapApiException(message, new IllegalStateException(message));
                    }
                    userId = fromList.userId();
                }
                created = true;
            } else {
                userId = existing.userId();
                if (!hasText(userId)) {
                    String message = "Found existing user without a userId: " + mutation.userName();
                    throw new ZapApiException(message, new IllegalStateException(message));
                }
            }

            if (hasText(mutation.authCredentialsConfigParams())) {
                zap.users.setAuthenticationCredentials(
                        mutation.contextId(),
                        userId,
                        mutation.authCredentialsConfigParams().trim()
                );
            }

            if (mutation.enabled() != null) {
                zap.users.setUserEnabled(mutation.contextId(), userId, Boolean.toString(mutation.enabled()));
            }

            UserRecord latest = toUserRecord(zap.users.getUserById(mutation.contextId(), userId));
            return new UserMutationResult(
                    mutation.contextId(),
                    userId,
                    latest != null && hasText(latest.userName()) ? latest.userName() : mutation.userName(),
                    latest != null ? latest.enabled() : mutation.enabled(),
                    created
            );
        } catch (ClientApiException e) {
            log.error("Failed to upsert user '{}' for context {}: {}",
                    mutation.userName(), mutation.contextId(), e.getMessage(), e);
            throw new ZapApiException("Failed to upsert user " + mutation.userName(), e);
        }
    }

    @Override
    public AuthenticationConfigResult configureContextAuthentication(AuthenticationConfigRequest request) {
        try {
            zap.authentication.setAuthenticationMethod(
                    request.contextId(),
                    request.authMethodName(),
                    request.authMethodConfigParams()
            );

            if (request.loggedInIndicatorRegex() != null) {
                zap.authentication.setLoggedInIndicator(request.contextId(), request.loggedInIndicatorRegex());
            }
            if (request.loggedOutIndicatorRegex() != null) {
                zap.authentication.setLoggedOutIndicator(request.contextId(), request.loggedOutIndicatorRegex());
            }

            return new AuthenticationConfigResult(
                    request.contextId(),
                    request.authMethodName(),
                    request.authMethodConfigParams() != null,
                    request.loggedInIndicatorRegex() != null,
                    request.loggedOutIndicatorRegex() != null
            );
        } catch (ClientApiException e) {
            log.error("Failed to configure authentication for context {}: {}", request.contextId(), e.getMessage(), e);
            throw new ZapApiException("Failed to configure authentication for context " + request.contextId(), e);
        }
    }

    @Override
    public AuthenticationDiagnostics testUserAuthentication(String contextId, String userId) {
        try {
            ApiResponse authResponse = zap.users.authenticateAsUser(contextId, userId);
            ApiResponse authStateResponse = zap.users.getAuthenticationState(contextId, userId);

            Boolean likelyAuthenticated = parseBoolean(
                    extractFirstValue(authStateResponse, "lastPollResult", "authenticated", "loggedIn")
            );
            if (likelyAuthenticated == null) {
                likelyAuthenticated = parseBoolean(
                        extractFirstValue(authResponse, "authSuccessful", "success", "result")
                );
            }

            return new AuthenticationDiagnostics(
                    contextId,
                    userId,
                    likelyAuthenticated,
                    authResponse.toString(0),
                    authStateResponse.toString(0)
            );
        } catch (ClientApiException e) {
            log.error("Failed to authenticate as user {} in context {}: {}", userId, contextId, e.getMessage(), e);
            throw new ZapApiException("Failed to authenticate as user " + userId, e);
        }
    }

    private List<String> listContextNames() throws ClientApiException {
        ApiResponse response = zap.context.contextList();
        return extractStringList(response).stream().filter(this::hasText).toList();
    }

    private ContextSnapshot loadContext(String contextName) throws ClientApiException {
        ApiResponse contextResponse = zap.context.context(contextName);
        String contextId = extractFirstValue(contextResponse, "id", "contextId");
        Boolean inScope = parseBoolean(extractFirstValue(contextResponse, "inScope", "isInScope", "booleanInScope"));

        List<String> includes = extractStringList(zap.context.includeRegexs(contextName));
        List<String> excludes = extractStringList(zap.context.excludeRegexs(contextName));

        return new ContextSnapshot(contextName, contextId, inScope, includes, excludes);
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

    private UserRecord toUserRecord(ApiResponse response) {
        if (!(response instanceof ApiResponseSet set)) {
            return null;
        }

        String userId = extractFirstValue(set, "id", "userId");
        String name = extractFirstValue(set, "name", "userName");
        Boolean enabled = parseBoolean(extractFirstValue(set, "enabled", "isEnabled"));

        return new UserRecord(userId, name, enabled);
    }

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

    private String normalizeOptionalValue(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record UserRecord(String userId, String userName, Boolean enabled) {
    }
}
