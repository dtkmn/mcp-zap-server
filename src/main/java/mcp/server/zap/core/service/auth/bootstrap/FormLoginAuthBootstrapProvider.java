package mcp.server.zap.core.service.auth.bootstrap;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        String targetUrl = requireText(request.targetUrl(), "targetUrl");
        String loginUrl = requireText(request.loginUrl(), "loginUrl");
        String username = requireText(request.username(), "username");
        String loggedInIndicatorRegex = requireText(request.loggedInIndicatorRegex(), "loggedInIndicatorRegex");
        validateUrls(targetUrl, loginUrl);

        String contextName = hasText(request.contextName()) ? request.contextName().trim() : deriveContextName(targetUrl);
        String userName = hasText(request.userName()) ? request.userName().trim() : DEFAULT_USER_NAME;
        String usernameField = hasText(request.usernameField()) ? request.usernameField().trim() : DEFAULT_USERNAME_FIELD;
        String passwordField = hasText(request.passwordField()) ? request.passwordField().trim() : DEFAULT_PASSWORD_FIELD;
        validateFormFieldName(usernameField, "usernameField");
        validateFormFieldName(passwordField, "passwordField");
        String resolvedSecret = credentialReferenceResolver.resolveSecret(request.credentialReference(), request.inlineSecret());

        List<String> includeRegexes = List.of(buildScopeRegex(targetUrl));
        List<String> excludeRegexes = List.of(buildLogoutRegex(targetUrl));
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
        Map<String, Object> userSummary = contextUserService.upsertUser(contextId, userName, authCredentialsConfigParams, true);
        String userId = stringValue(userSummary.get("userId"));

        PreparedAuthSession session = new PreparedAuthSession(
                UUID.randomUUID().toString(),
                defaultSessionLabel(request.sessionLabel(), targetUrl, request.authKind()),
                request.authKind(),
                providerId(),
                new TargetDescriptor(TargetDescriptor.Kind.WEB, targetUrl, contextName),
                trimToNull(request.credentialReference()),
                contextName,
                contextId,
                userName,
                userId,
                null,
                loginUrl,
                true
        );

        List<String> warnings = new ArrayList<>();
        if (!hasText(request.credentialReference()) && hasText(request.inlineSecret())) {
            warnings.add("Inline secret was accepted for this local workflow. Prefer credentialReference for repeatable operator paths.");
        }
        warnings.add("Run zap_auth_session_validate before authenticated crawl or attack flows.");

        return new AuthSessionPrepareResult(session, warnings);
    }

    @Override
    public AuthSessionValidationResult validate(PreparedAuthSession session) {
        Map<String, Object> result = contextUserService.testUserAuthentication(session.contextId(), session.userId());
        boolean valid = !Boolean.FALSE.equals(result.get("likelyAuthenticated"));
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("likelyAuthenticated=" + result.get("likelyAuthenticated"));
        diagnostics.add("contextId=" + session.contextId());
        diagnostics.add("userId=" + session.userId());
        diagnostics.add("Use zap_context_auth_configure or zap_user_upsert for lower-level fixes if indicators or credentials are wrong.");
        return new AuthSessionValidationResult(
                session,
                valid,
                valid ? "authenticated" : "authentication_failed",
                diagnostics
        );
    }

    private String buildScopeRegex(String targetUrl) {
        URI uri = URI.create(targetUrl);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String path = normalizePathPrefix(uri.getPath());
        return Pattern.quote(base + path) + ".*";
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

    private void validateUrls(String targetUrl, String loginUrl) {
        urlValidationService.validateUrl(targetUrl);
        urlValidationService.validateUrl(loginUrl);

        URI target = URI.create(targetUrl);
        URI login = URI.create(loginUrl);
        if (!sameOrigin(target, login)) {
            throw new IllegalArgumentException("loginUrl must share the same origin as targetUrl");
        }
    }

    private boolean sameOrigin(URI target, URI login) {
        return stringEqualsIgnoreCase(target.getScheme(), login.getScheme())
                && stringEqualsIgnoreCase(target.getHost(), login.getHost())
                && effectivePort(target) == effectivePort(login);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

    private boolean stringEqualsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String buildLogoutRegex(String targetUrl) {
        URI uri = URI.create(targetUrl);
        return Pattern.quote(uri.getScheme() + "://" + uri.getAuthority() + "/logout") + ".*";
    }

    private String deriveContextName(String targetUrl) {
        URI uri = URI.create(targetUrl);
        String host = uri.getHost() == null ? "target" : uri.getHost().toLowerCase(Locale.ROOT).replace('.', '-');
        return host + "-auth";
    }

    private String defaultSessionLabel(String sessionLabel, String targetUrl, AuthBootstrapKind authKind) {
        if (hasText(sessionLabel)) {
            return sessionLabel.trim();
        }
        URI uri = URI.create(targetUrl);
        String host = uri.getHost() == null ? "target" : uri.getHost().toLowerCase(Locale.ROOT);
        return host + "-" + authKind.wireValue();
    }

    private String normalizePathPrefix(String path) {
        if (!hasText(path) || "/".equals(path)) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
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
