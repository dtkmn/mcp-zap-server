package mcp.server.zap.core.service.auth.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves immutable operator-managed authentication profiles for MCP callers.
 */
@Component
public class AuthProfileResolver {
    private static final int MAX_INDICATOR_REGEX_LENGTH = 512;
    private static final Pattern PROFILE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Map<String, ConfiguredAuthProfile> profiles;

    public AuthProfileResolver(AuthBootstrapProperties properties) {
        this.profiles = compileProfiles(properties == null ? List.of() : properties.getProfiles());
    }

    public AuthBootstrapRequest resolve(String profileId, String targetUrl) {
        String normalizedProfileId = requireProfileId(profileId);
        ConfiguredAuthProfile profile = profiles.get(normalizedProfileId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown auth profile ID: " + normalizedProfileId);
        }
        return profile.requestFor(requireText(targetUrl, "targetUrl"));
    }

    private Map<String, ConfiguredAuthProfile> compileProfiles(List<AuthBootstrapProperties.Profile> configuredProfiles) {
        Map<String, ConfiguredAuthProfile> compiled = new LinkedHashMap<>();
        if (configuredProfiles == null) {
            return Map.of();
        }
        for (AuthBootstrapProperties.Profile configured : configuredProfiles) {
            if (configured == null) {
                throw new IllegalArgumentException("Auth profile configuration cannot contain null entries");
            }
            ConfiguredAuthProfile profile = compileProfile(configured);
            if (compiled.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("Duplicate auth profile ID: " + profile.id());
            }
        }
        return Map.copyOf(compiled);
    }

    private ConfiguredAuthProfile compileProfile(AuthBootstrapProperties.Profile configured) {
        String id = requireProfileId(configured.getId());
        AuthBootstrapKind authKind;
        try {
            authKind = AuthBootstrapKind.fromWireValue(configured.getKind());
        } catch (IllegalArgumentException e) {
            throw invalidProfile(id, e.getMessage(), e);
        }
        HttpOrigin allowedOrigin;
        try {
            allowedOrigin = HttpOrigin.fromConfiguredOrigin(configured.getAllowedOrigin());
        } catch (IllegalArgumentException e) {
            throw invalidProfile(id, e.getMessage(), e);
        }

        String credentialReference = requireProfileText(id, configured.getCredentialReference(), "credentialReference");
        try {
            CredentialReferenceResolver.validateReference(credentialReference);
        } catch (IllegalArgumentException e) {
            throw invalidProfile(id, e.getMessage(), e);
        }
        String loginUrl = trimToNull(configured.getLoginUrl());
        String username = trimToNull(configured.getUsername());
        String loggedInIndicatorRegex = validateIndicatorRegex(
                id,
                configured.getLoggedInIndicatorRegex(),
                "loggedInIndicatorRegex"
        );
        String loggedOutIndicatorRegex = validateIndicatorRegex(
                id,
                configured.getLoggedOutIndicatorRegex(),
                "loggedOutIndicatorRegex"
        );
        if (authKind == AuthBootstrapKind.FORM) {
            loginUrl = requireProfileText(id, loginUrl, "loginUrl");
            username = requireProfileText(id, username, "username");
            loggedInIndicatorRegex = requireProfileText(id, loggedInIndicatorRegex, "loggedInIndicatorRegex");
            try {
                allowedOrigin.requireMatch(loginUrl, "loginUrl");
            } catch (IllegalArgumentException e) {
                throw invalidProfile(id, e.getMessage(), e);
            }
        }

        return new ConfiguredAuthProfile(
                id,
                authKind,
                allowedOrigin,
                credentialReference,
                loginUrl,
                username,
                trimToNull(configured.getZapUserName()),
                trimToNull(configured.getUsernameField()),
                trimToNull(configured.getPasswordField()),
                trimToNull(configured.getHeaderName()),
                loggedInIndicatorRegex,
                loggedOutIndicatorRegex
        );
    }

    private String validateIndicatorRegex(String profileId, String value, String fieldName) {
        String regex = trimToNull(value);
        if (regex == null) {
            return null;
        }
        if (regex.length() > MAX_INDICATOR_REGEX_LENGTH) {
            throw invalidProfile(
                    profileId,
                    fieldName + " cannot exceed " + MAX_INDICATOR_REGEX_LENGTH + " characters"
            );
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw invalidProfile(profileId, fieldName + " must be a valid regular expression", e);
        }
        return regex;
    }

    private String requireProfileId(String value) {
        String id = requireText(value, "profileId");
        if (!PROFILE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("profileId must use 1-64 letters, numbers, dots, underscores, or dashes");
        }
        return id;
    }

    private String requireProfileText(String profileId, String value, String fieldName) {
        try {
            return requireText(value, fieldName);
        } catch (IllegalArgumentException e) {
            throw invalidProfile(profileId, e.getMessage(), e);
        }
    }

    private IllegalArgumentException invalidProfile(String profileId, String message, Exception cause) {
        return new IllegalArgumentException("Auth profile '" + profileId + "' is invalid: " + message, cause);
    }

    private IllegalArgumentException invalidProfile(String profileId, String message) {
        return new IllegalArgumentException("Auth profile '" + profileId + "' is invalid: " + message);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ConfiguredAuthProfile(
            String id,
            AuthBootstrapKind authKind,
            HttpOrigin allowedOrigin,
            String credentialReference,
            String loginUrl,
            String username,
            String zapUserName,
            String usernameField,
            String passwordField,
            String headerName,
            String loggedInIndicatorRegex,
            String loggedOutIndicatorRegex
    ) {
        private AuthBootstrapRequest requestFor(String targetUrl) {
            return new AuthBootstrapRequest(
                    id,
                    targetUrl,
                    authKind,
                    allowedOrigin,
                    credentialReference,
                    loginUrl,
                    username,
                    zapUserName,
                    usernameField,
                    passwordField,
                    headerName,
                    loggedInIndicatorRegex,
                    loggedOutIndicatorRegex
            );
        }
    }
}
