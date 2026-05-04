package mcp.server.zap.core.service.auth.bootstrap;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import mcp.server.zap.core.service.ContextUserService;
import mcp.server.zap.core.service.UrlValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuidedAuthSessionServiceTest {

    @TempDir
    Path tempDir;

    private ContextUserService contextUserService;
    private UrlValidationService urlValidationService;
    private GuidedAuthSessionService service;

    @BeforeEach
    void setUp() {
        contextUserService = mock(ContextUserService.class);
        urlValidationService = mock(UrlValidationService.class);

        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setAllowInlineSecrets(false);
        properties.setAllowedCredentialReferences(List.of(
                "file:" + tempDir.toAbsolutePath().normalize() + "/*",
                "env:MCP_ZAP_AUTH_BOOTSTRAP_TEST_MISSING_ENV"
        ));

        CredentialReferenceResolver credentialReferenceResolver = new CredentialReferenceResolver(properties);
        PreparedAuthSessionRegistry registry = new InMemoryPreparedAuthSessionRegistry();

        service = new GuidedAuthSessionService(
                List.of(
                        new FormLoginAuthBootstrapProvider(contextUserService, credentialReferenceResolver, urlValidationService),
                        new HeaderCredentialAuthBootstrapProvider(credentialReferenceResolver, urlValidationService)
                ),
                registry
        );
    }

    @Test
    void prepareAndValidateFormSessionUsesExistingContextUserFlow() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);

        when(contextUserService.upsertContext(
                eq("shop-auth"),
                eq(List.of(scopeRegex("https://shop.example.com"))),
                eq(List.of(logoutRegex("https://shop.example.com"))),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq(authMethodConfig("https://shop.example.com/login", "username", "password")),
                eq(".*Logout.*"),
                eq(".*Sign in.*")
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.upsertUser(
                eq("1"),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", "7", "userName", "zap-scan-user"));
        when(contextUserService.testUserAuthentication("1", "7"))
                .thenReturn(Map.of("likelyAuthenticated", true));

        String prepareResponse = service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://shop.example.com/login",
                "zap-scan-user",
                "zap-scan-user",
                "username",
                "password",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        );

        assertThat(prepareResponse).contains("Guided auth session prepared.");
        assertThat(prepareResponse).contains("Auth Kind: form");
        assertThat(prepareResponse).contains("Context ID: 1");
        assertThat(prepareResponse).contains("User ID: 7");
        assertThat(prepareResponse).contains("Next Step: call zap_auth_session_validate");
        assertThat(prepareResponse).doesNotContain("example-password-value");
        verify(urlValidationService).validateUrl("https://shop.example.com");
        verify(urlValidationService).validateUrl("https://shop.example.com/login");

        String sessionId = extractSessionId(prepareResponse);
        String validateResponse = service.validateSession(sessionId);

        assertThat(validateResponse).contains("Guided auth session validation complete.");
        assertThat(validateResponse).contains("Valid: true");
        assertThat(validateResponse).contains("Outcome: authenticated");
        assertThat(validateResponse).doesNotContain("example-password-value");
        verify(contextUserService).testUserAuthentication("1", "7");
    }

    @Test
    void prepareHeaderSessionUsesCredentialReferenceWithoutPretendingEngineBindingExists() throws Exception {
        Path tokenFile = tempDir.resolve("api-token.txt");
        Files.writeString(tokenFile, "api-token-value", StandardCharsets.UTF_8);

        String prepareResponse = service.prepareSession(
                "https://api.example.com",
                "api-key",
                "file:" + tokenFile,
                null,
                "orders-api-key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(prepareResponse).contains("Guided auth session prepared.");
        assertThat(prepareResponse).contains("Auth Kind: api-key");
        assertThat(prepareResponse).contains("Engine Binding: gateway contract only");
        assertThat(prepareResponse).contains("Header Name: X-API-Key");
        verify(urlValidationService).validateUrl("https://api.example.com");

        String sessionId = extractSessionId(prepareResponse);
        String validateResponse = service.validateSession(sessionId);

        assertThat(validateResponse).contains("Valid: true");
        assertThat(validateResponse).contains("Outcome: reference_valid");
        assertThat(validateResponse).contains("Current guided ZAP flows do not automatically inject header-based auth yet.");
    }

    @Test
    void credentialReferencesMustBeOperatorAllowlisted() {
        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "api-key",
                "env:HOME",
                null,
                "orders-api-key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credentialReference is not in the operator allowlist");
        assertRunbookDocuments("credentialReference is not in the operator allowlist");
    }

    @Test
    void formLoginRejectsCrossOriginLoginUrlBeforeConfiguringZap() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://evil.example.test/login",
                "zap-scan-user",
                "zap-scan-user",
                "username",
                "password",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("loginUrl must share the same origin as targetUrl");

        verify(urlValidationService).validateUrl("https://shop.example.com");
        verify(urlValidationService).validateUrl("https://evil.example.test/login");
        verifyNoInteractions(contextUserService);
    }

    @Test
    void formLoginEncodesAuthMethodConfigParamsToBlockParameterInjection() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);
        String loginUrl = "https://shop.example.com/login?x=1&loginUrl=https://attacker.example/steal";

        when(contextUserService.upsertContext(
                eq("shop-auth"),
                eq(List.of(scopeRegex("https://shop.example.com"))),
                eq(List.of(logoutRegex("https://shop.example.com"))),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.upsertUser(
                eq("1"),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", "7", "userName", "zap-scan-user"));

        service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                loginUrl,
                "zap-scan-user",
                "zap-scan-user",
                "user.name",
                "pass-word",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        );

        verify(contextUserService).configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq(authMethodConfig(loginUrl, "user.name", "pass-word")),
                eq(".*Logout.*"),
                eq(".*Sign in.*")
        );
    }

    @Test
    void formLoginRejectsUnsafeFieldNamesBeforeConfiguringZap() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://shop.example.com/login",
                "zap-scan-user",
                "zap-scan-user",
                "username&loginUrl=https://attacker.example/steal",
                "password",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usernameField contains unsupported characters");

        verify(urlValidationService).validateUrl("https://shop.example.com");
        verify(urlValidationService).validateUrl("https://shop.example.com/login");
        verifyNoInteractions(contextUserService);
    }

    @Test
    void formLoginRejectsUnsafePasswordFieldBeforeConfiguringZap() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://shop.example.com/login",
                "zap-scan-user",
                "zap-scan-user",
                "username",
                "password&loginUrl=https://attacker.example/steal",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordField contains unsupported characters");

        verify(urlValidationService).validateUrl("https://shop.example.com");
        verify(urlValidationService).validateUrl("https://shop.example.com/login");
        verifyNoInteractions(contextUserService);
    }

    @Test
    void formLoginEscapesContextScopeRegexLiterals() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);
        String targetUrl = "https://shop.example.com/app.v1/(beta)";
        String expectedIncludeRegex = scopeRegex(targetUrl);

        when(contextUserService.upsertContext(
                eq("shop-auth"),
                eq(List.of(expectedIncludeRegex)),
                eq(List.of(logoutRegex(targetUrl))),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.upsertUser(
                eq("1"),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", "7", "userName", "zap-scan-user"));

        service.prepareSession(
                targetUrl,
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://shop.example.com/app.v1/(beta)/login",
                "zap-scan-user",
                "zap-scan-user",
                "username",
                "password",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        );

        Pattern scopePattern = Pattern.compile(expectedIncludeRegex);
        assertThat(scopePattern.matcher("https://shop.example.com/app.v1/(beta)/checkout").matches()).isTrue();
        assertThat(scopePattern.matcher("https://shopXexampleYcom/app.v1/(beta)/checkout").matches()).isFalse();
        assertThat(scopePattern.matcher("https://shop.example.com/appXv1/(beta)/checkout").matches()).isFalse();
    }

    @Test
    void inlineSecretsAreRejectedByDefault() {
        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "bearer",
                null,
                "inline-value-for-local-test",
                "local-bearer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlineSecret is disabled by default");
        assertRunbookDocuments("inlineSecret is disabled by default");
    }

    @Test
    void missingCredentialReferenceHasDocumentedFailure() {
        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "bearer",
                null,
                null,
                "orders-bearer",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Provide credentialReference or inlineSecret");
        assertRunbookDocuments("Provide credentialReference or inlineSecret");
    }

    @Test
    void missingEnvironmentCredentialReferenceHasDocumentedFailure() {
        String envName = "MCP_ZAP_AUTH_BOOTSTRAP_TEST_MISSING_ENV";

        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "api-key",
                "env:" + envName,
                null,
                "orders-api-key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Environment variable '" + envName + "' is missing or blank");
        assertRunbookDocuments("Environment variable ... is missing or blank");
    }

    @Test
    void unreadableFileCredentialReferenceHasDocumentedFailure() {
        Path missingFile = tempDir.resolve("missing-secret.txt");

        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "api-key",
                "file:" + missingFile,
                null,
                "orders-api-key",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unable to read credentialReference file");
        assertRunbookDocuments("Unable to read credentialReference file");
    }

    @Test
    void badAuthKindHasDocumentedFailure() {
        assertThatThrownBy(() -> service.prepareSession(
                "https://api.example.com",
                "session-cookie",
                "env:MCP_ZAP_AUTH_BOOTSTRAP_TEST_MISSING_ENV",
                null,
                "unsupported-auth",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authKind must be one of: form, bearer, api-key");
        assertRunbookDocuments("authKind must be one of: form, bearer, api-key");
    }

    @Test
    void unknownSessionHasDocumentedFailure() {
        assertThatThrownBy(() -> service.validateSession("missing-session"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown auth session ID: missing-session");
        assertRunbookDocuments("Unknown auth session ID");
    }

    @Test
    void failedFormValidationHasDocumentedOutcomeAndNoSecretLeakage() throws Exception {
        Path passwordFile = tempDir.resolve("scan-password.txt");
        Files.writeString(passwordFile, "example-password-value", StandardCharsets.UTF_8);

        when(contextUserService.upsertContext(
                eq("shop-auth"),
                eq(List.of(scopeRegex("https://shop.example.com"))),
                eq(List.of(logoutRegex("https://shop.example.com"))),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq(authMethodConfig("https://shop.example.com/login", "username", "password")),
                eq(".*Logout.*"),
                eq(".*Sign in.*")
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.upsertUser(
                eq("1"),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", "7", "userName", "zap-scan-user"));
        when(contextUserService.testUserAuthentication("1", "7"))
                .thenReturn(Map.of("likelyAuthenticated", false));

        String prepareResponse = service.prepareSession(
                "https://shop.example.com",
                "form",
                "file:" + passwordFile,
                null,
                "shop-form-auth",
                "shop-auth",
                "https://shop.example.com/login",
                "zap-scan-user",
                "zap-scan-user",
                "username",
                "password",
                null,
                ".*Logout.*",
                ".*Sign in.*"
        );

        String validateResponse = service.validateSession(extractSessionId(prepareResponse));

        assertThat(validateResponse).contains("Valid: false");
        assertThat(validateResponse).contains("Outcome: authentication_failed");
        assertThat(validateResponse).contains("likelyAuthenticated=false");
        assertThat(validateResponse).doesNotContain("example-password-value");
        assertRunbookDocuments("authentication_failed");
    }

    @Test
    void runbookDocumentsCurrentFailureTaxonomy() throws Exception {
        String runbook = authRunbook();

        assertThat(runbook).contains(
                "authKind must be one of: form, bearer, api-key",
                "Provide credentialReference or inlineSecret",
                "Environment variable ... is missing or blank",
                "Unable to read credentialReference file",
                "reference_missing",
                "authentication_failed",
                "Unknown auth session ID",
                "form-login prepare and validate"
        );
        assertThat(runbook).doesNotContain("top-secret-token", "StrongPassword123!");
    }

    private String extractSessionId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Session ID: ")) {
                return line.substring("Session ID: ".length()).trim();
            }
        }
        throw new AssertionError("No session ID found in response: " + response);
    }

    private void assertRunbookDocuments(String expectedText) {
        assertThat(authRunbook()).contains(expectedText);
    }

    private String authMethodConfig(String loginUrl, String usernameField, String passwordField) {
        String loginRequestData = usernameField + "={%username%}&" + passwordField + "={%password%}";
        return "loginUrl=" + urlEncode(loginUrl) + "&loginRequestData=" + urlEncode(loginRequestData);
    }

    private String scopeRegex(String targetUrl) {
        URI uri = URI.create(targetUrl);
        String path = normalizePathPrefix(uri.getPath());
        return Pattern.quote(uri.getScheme() + "://" + uri.getAuthority() + path) + ".*";
    }

    private String logoutRegex(String targetUrl) {
        URI uri = URI.create(targetUrl);
        return Pattern.quote(uri.getScheme() + "://" + uri.getAuthority() + "/logout") + ".*";
    }

    private String normalizePathPrefix(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "/";
        }
        return path.endsWith("/") ? path : path + "/";
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String authRunbook() {
        try {
            return Files.readString(Path.of("docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md"));
        } catch (Exception e) {
            throw new AssertionError("Unable to read auth bootstrap failure runbook", e);
        }
    }
}
