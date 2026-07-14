package mcp.server.zap.core.service.auth.bootstrap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import mcp.server.zap.core.gateway.TargetDescriptor;
import mcp.server.zap.core.service.ContextUserService;
import mcp.server.zap.core.service.UrlValidationService;
import org.springframework.stereotype.Component;

/**
 * Guided provider for simple form-login bootstrapping backed by existing ZAP context/user APIs.
 */
@Component
public class FormLoginAuthBootstrapProvider implements AuthBootstrapProvider {
    private static final String CONTEXT_NAME_SUFFIX = "-auth";
    private static final String DEFAULT_USERNAME_FIELD = "username";
    private static final String DEFAULT_PASSWORD_FIELD = "password";
    private static final String DEFAULT_USER_NAME = "zap-scan-user";
    private static final Pattern SAFE_FORM_FIELD_NAME = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]{1,128}");

    private final ContextUserService contextUserService;
    private final CredentialReferenceResolver credentialReferenceResolver;
    private final UrlValidationService urlValidationService;

    public FormLoginAuthBootstrapProvider(ContextUserService contextUserService,
                                          CredentialReferenceResolver credentialReferenceResolver,
                                          UrlValidationService urlValidationService) {
        this.contextUserService = contextUserService;
        this.credentialReferenceResolver = credentialReferenceResolver;
        this.urlValidationService = urlValidationService;
    }

    @Override
    public String providerId() {
        return "zap-form-login";
    }

    @Override
    public boolean supports(AuthBootstrapRequest request) {
        return request.authKind() == AuthBootstrapKind.FORM;
    }

    @Override
    public AuthSessionPrepareResult prepare(AuthBootstrapRequest request) {
        String profileId = requireText(request.profileId(), "profileId");
        String targetUrl = requireText(request.targetUrl(), "targetUrl");
        String loginUrl = requireText(request.loginUrl(), "loginUrl");
        String username = requireText(request.username(), "username");
        String loggedInIndicatorRegex = requireText(request.loggedInIndicatorRegex(), "loggedInIndicatorRegex");
        validateUrls(request.allowedOrigin(), targetUrl, loginUrl);

        String contextName = profileId + CONTEXT_NAME_SUFFIX;
        String zapUserName = hasText(request.zapUserName()) ? request.zapUserName().trim() : DEFAULT_USER_NAME;
        String usernameField = hasText(request.usernameField()) ? request.usernameField().trim() : DEFAULT_USERNAME_FIELD;
        String passwordField = hasText(request.passwordField()) ? request.passwordField().trim() : DEFAULT_PASSWORD_FIELD;
        validateFormFieldName(usernameField, "usernameField");
        validateFormFieldName(passwordField, "passwordField");
        String resolvedSecret = credentialReferenceResolver.resolveSecret(request.credentialReference());

        List<String> includeRegexes = List.of(buildScopeRegex(request.allowedOrigin()));
        List<String> excludeRegexes = List.of(buildLogoutRegex(request.allowedOrigin()));
        Map<String, Object> contextSummary = contextUserService.upsertContext(contextName, includeRegexes, excludeRegexes, true);
        String contextId = stringValue(contextSummary.get("contextId"));

        String authMethodConfigParams = buildAuthMethodConfigParams(loginUrl, usernameField, passwordField);
        contextUserService.configureContextAuthentication(
                contextId,
                "formBasedAuthentication",
                authMethodConfigParams,
                loggedInIndicatorRegex,
                trimToNull(request.loggedOutIndicatorRegex())
        );

        String authCredentialsConfigParams = "username="
                + urlEncode(username)
                + "&password="
                + urlEncode(resolvedSecret);
        Map<String, Object> userSummary = contextUserService.upsertUser(contextId, zapUserName, authCredentialsConfigParams, true);
        String userId = stringValue(userSummary.get("userId"));

        PreparedAuthSession session = new PreparedAuthSession(
                UUID.randomUUID().toString(),
                profileId,
                request.authKind(),
                providerId(),
                new TargetDescriptor(TargetDescriptor.Kind.WEB, targetUrl, contextName),
                request.allowedOrigin(),
                trimToNull(request.credentialReference()),
                contextName,
                contextId,
                zapUserName,
                userId,
                null,
                loginUrl,
                true
        );

        List<String> warnings = new ArrayList<>();
        warnings.add("Run zap_auth_session_validate before authenticated crawl or attack flows.");

        return new AuthSessionPrepareResult(session, warnings);
    }

    @Override
    public AuthSessionValidationResult validate(PreparedAuthSession session) {
        requireSessionOrigin(session);
        Map<String, Object> result = contextUserService.testUserAuthentication(session.contextId(), session.userId());
        boolean valid = Boolean.TRUE.equals(result.get("likelyAuthenticated"));
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("likelyAuthenticated=" + result.get("likelyAuthenticated"));
        diagnostics.add("contextId=" + session.contextId());
        diagnostics.add("userId=" + session.userId());
        diagnostics.add("Fix the operator-managed auth profile and re-run zap_auth_session_prepare if indicators or credentials are wrong.");
        return new AuthSessionValidationResult(
                session,
                valid,
                valid ? "authenticated" : "authentication_failed",
                diagnostics
        );
    }

    private String buildScopeRegex(HttpOrigin allowedOrigin) {
        return buildOriginRegexPrefix(allowedOrigin) + "(?:[/?#].*)?\\z";
    }

    private String buildAuthMethodConfigParams(String loginUrl, String usernameField, String passwordField) {
        String loginRequestData = usernameField
                + "={%username%}&"
                + passwordField
                + "={%password%}";
        return configParam("loginUrl", loginUrl)
                + "&"
                + configParam("loginRequestData", loginRequestData);
    }

    private String configParam(String key, String value) {
        return urlEncode(key) + "=" + urlEncode(value);
    }

    private void validateFormFieldName(String fieldName, String fieldLabel) {
        if (!SAFE_FORM_FIELD_NAME.matcher(fieldName).matches()) {
            throw new IllegalArgumentException(
                    fieldLabel + " contains unsupported characters. Use letters, numbers, dot, underscore, dash, colon, or brackets."
            );
        }
    }

    private void validateUrls(HttpOrigin allowedOrigin, String targetUrl, String loginUrl) {
        if (allowedOrigin == null) {
            throw new IllegalArgumentException("Auth profile allowedOrigin cannot be null");
        }
        urlValidationService.validateUrl(targetUrl);
        urlValidationService.validateUrl(loginUrl);
        allowedOrigin.requireMatch(targetUrl, "targetUrl");
        allowedOrigin.requireMatch(loginUrl, "loginUrl");
    }

    private String buildLogoutRegex(HttpOrigin allowedOrigin) {
        return buildOriginRegexPrefix(allowedOrigin) + Pattern.quote("/logout") + "(?:[/?#].*)?\\z";
    }

    private String buildOriginRegexPrefix(HttpOrigin allowedOrigin) {
        String host = allowedOrigin.host().contains(":")
                ? "[" + allowedOrigin.host() + "]"
                : allowedOrigin.host();
        int defaultPort = "https".equals(allowedOrigin.scheme()) ? 443 : 80;
        String portRegex = allowedOrigin.port() == defaultPort
                ? "(?::0*" + defaultPort + ")?"
                : ":0*" + allowedOrigin.port();
        return "\\A(?i:"
                + Pattern.quote(allowedOrigin.scheme() + "://" + host)
                + ")"
                + portRegex;
    }

    private void requireSessionOrigin(PreparedAuthSession session) {
        if (session.authorizedOrigin() == null) {
            throw new IllegalArgumentException("Prepared auth session has no authorized profile origin");
        }
        session.authorizedOrigin().requireMatch(session.target().baseUrl(), "auth session target");
        session.authorizedOrigin().requireMatch(session.loginUrl(), "auth session loginUrl");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(fieldName + " cannot be null or blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
