package mcp.server.zap.core.service.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;

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
        service = serviceWith(
                formProfile("shop-form", "https://shop.example.com", "file:" + formPasswordFile()),
                headerProfile("orders-api-key", "https://api.example.com", "file:" + apiTokenFile())
        );
    }

    @Test
    void prepareAndValidateFormSessionUsesOperatorProfile() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        stubFormUser("shop-form-auth", "1", "7");
        when(contextUserService.testUserAuthentication("1", "7"))
                .thenReturn(Map.of("likelyAuthenticated", true));

        String prepareResponse = service.prepareSession("shop-form", "https://shop.example.com");

        assertThat(prepareResponse).contains(
                "Guided auth session prepared.",
                "Auth Profile: shop-form",
                "Auth Kind: form",
                "Authorized Origin: https://shop.example.com",
                "Context ID: 1",
                "User ID: 7",
                "Next Step: call zap_auth_session_validate"
        );
        assertThat(prepareResponse).doesNotContain("example-password-value", "scan-password.txt");
        verify(urlValidationService).validateUrl("https://shop.example.com");
        verify(urlValidationService).validateUrl("https://shop.example.com/login");

        String validateResponse = service.validateSession(extractSessionId(prepareResponse));

        assertThat(validateResponse).contains(
                "Guided auth session validation complete.",
                "Valid: true",
                "Outcome: authenticated"
        );
        assertThat(validateResponse).doesNotContain("example-password-value", "scan-password.txt");
        verify(contextUserService).testUserAuthentication("1", "7");
    }

    @Test
    void prepareHeaderSessionKeepsExistingGatewayOnlyBehavior() throws Exception {
        Files.writeString(apiTokenFile(), "api-token-value", StandardCharsets.UTF_8);

        String prepareResponse = service.prepareSession("orders-api-key", "https://api.example.com/orders");

        assertThat(prepareResponse).contains(
                "Auth Profile: orders-api-key",
                "Auth Kind: api-key",
                "Authorized Origin: https://api.example.com",
                "Engine Binding: gateway contract only",
                "Header Name: X-API-Key"
        );
        assertThat(prepareResponse).doesNotContain("api-token-value", "api-token.txt");
        verify(urlValidationService).validateUrl("https://api.example.com/orders");

        String validateResponse = service.validateSession(extractSessionId(prepareResponse));
        assertThat(validateResponse).contains(
                "Valid: true",
                "Outcome: reference_valid",
                "Current guided ZAP flows do not automatically inject header-based auth yet."
        );
    }

    @Test
    void formCredentialCannotBeReboundToCallerSelectedOrigin() throws Exception {
        assertThatThrownBy(() -> service.prepareSession("shop-form", "https://attacker.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("targetUrl origin is not authorized for auth profile");

        verify(urlValidationService).validateUrl("https://attacker.example");
        verify(urlValidationService).validateUrl("https://shop.example.com/login");
        verifyNoInteractions(contextUserService);
    }

    @Test
    void profileAllowsCallerSelectedPathOnAuthorizedOrigin() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        stubFormUser("shop-form-auth", "3", "8");

        String response = service.prepareSession("shop-form", "https://shop.example.com/admin");

        assertThat(response).contains("Target URL: https://shop.example.com/admin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sameProfileContextScopeIsImmutableAcrossCallerPaths() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        when(contextUserService.upsertContext(eq("shop-form-auth"), anyList(), anyList(), eq(true)))
                .thenReturn(Map.of("contextId", "1"));
        when(contextUserService.upsertUser(
                eq("1"),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", "7", "userName", "zap-scan-user"));

        service.prepareSession("shop-form", "https://shop.example.com/admin");
        service.prepareSession("shop-form", "https://shop.example.com/orders");

        ArgumentCaptor<List<String>> scopes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> exclusions = ArgumentCaptor.forClass(List.class);
        verify(contextUserService, times(2)).upsertContext(
                eq("shop-form-auth"),
                scopes.capture(),
                exclusions.capture(),
                eq(true)
        );
        List<String> firstScope = scopes.getAllValues().get(0);
        List<String> secondScope = scopes.getAllValues().get(1);
        assertThat(firstScope).isEqualTo(secondScope).hasSize(1);
        assertThat(exclusions.getAllValues().get(0))
                .isEqualTo(exclusions.getAllValues().get(1))
                .hasSize(1);

        String profileScope = firstScope.get(0);
        assertThat(Pattern.matches(profileScope, "https://shop.example.com")).isTrue();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com/admin")).isTrue();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com/admin/users")).isTrue();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com/orders")).isTrue();
        assertThat(Pattern.matches(profileScope, "HTTPS://SHOP.EXAMPLE.COM:443/orders")).isTrue();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com./orders")).isFalse();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com.attacker.test/admin")).isFalse();
        assertThat(Pattern.matches(profileScope, "https://shop.example.com:8443/admin")).isFalse();
        assertThat(Pattern.matches(profileScope, "http://shop.example.com/admin")).isFalse();
    }

    @Test
    void eachProfileOwnsADistinctGuidedZapContext() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        service = serviceWith(
                formProfile("shop-a", "https://shop.example.com", "file:" + formPasswordFile()),
                formProfile("shop-b", "https://shop.example.com", "file:" + formPasswordFile())
        );
        stubFormUser("shop-a-auth", "11", "21");
        stubFormUser("shop-b-auth", "12", "22");

        String first = service.prepareSession("shop-a", "https://shop.example.com");
        String second = service.prepareSession("shop-b", "https://shop.example.com");

        assertThat(first).contains("Context Name: shop-a-auth");
        assertThat(second).contains("Context Name: shop-b-auth");
    }

    @Test
    void formLoginEncodesProfileConfigurationToBlockParameterInjection() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        String loginUrl = "https://shop.example.com/login?x=1&loginUrl=https://attacker.example/steal";
        AuthBootstrapProperties.Profile profile = formProfile(
                "shop-form",
                "https://shop.example.com",
                "file:" + formPasswordFile()
        );
        profile.setLoginUrl(loginUrl);
        profile.setUsernameField("user.name");
        profile.setPasswordField("pass-word");
        service = serviceWith(profile);
        stubFormUser("shop-form-auth", "1", "7");

        service.prepareSession("shop-form", "https://shop.example.com");

        verify(contextUserService).configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq(authMethodConfig(loginUrl, "user.name", "pass-word")),
                eq(".*Logout.*"),
                eq(".*Sign in.*")
        );
    }

    @Test
    void formLoginRejectsUnsafeProfileFieldBeforeReadingSecretOrConfiguringZap() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        AuthBootstrapProperties.Profile profile = formProfile(
                "shop-form",
                "https://shop.example.com",
                "file:" + formPasswordFile()
        );
        profile.setUsernameField("username&loginUrl=https://attacker.example/steal");
        service = serviceWith(profile);

        assertThatThrownBy(() -> service.prepareSession("shop-form", "https://shop.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usernameField contains unsupported characters");

        verifyNoInteractions(contextUserService);
    }

    @Test
    void missingProfileCredentialFailsBeforeZapMutation() {
        String envName = "MCP_ZAP_AUTH_PROFILE_TEST_MISSING";
        AuthBootstrapProperties.Profile profile = formProfile(
                "missing-env",
                "https://shop.example.com",
                "env:" + envName
        );
        service = serviceWith(profile);

        assertThatThrownBy(() -> service.prepareSession("missing-env", "https://shop.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();

        verifyNoInteractions(contextUserService);
        assertRunbookDocuments("Auth profile credential could not be resolved");
    }

    @Test
    void unreadableProfileFileFailsBeforeZapMutation() {
        AuthBootstrapProperties.Profile profile = formProfile(
                "missing-file",
                "https://shop.example.com",
                "file:" + tempDir.resolve("missing-secret")
        );
        service = serviceWith(profile);

        assertThatThrownBy(() -> service.prepareSession("missing-file", "https://shop.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();

        verifyNoInteractions(contextUserService);
        assertRunbookDocuments("Auth profile credential could not be resolved");
    }

    @Test
    void unknownProfileHasDocumentedFailure() {
        assertThatThrownBy(() -> service.prepareSession("missing", "https://shop.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown auth profile ID: missing");

        verifyNoInteractions(contextUserService);
        assertRunbookDocuments("Unknown auth profile ID");
    }

    @Test
    void failedFormValidationHasDocumentedOutcomeAndNoSecretLeakage() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        stubFormUser("shop-form-auth", "1", "7");
        when(contextUserService.testUserAuthentication("1", "7"))
                .thenReturn(Map.of("likelyAuthenticated", false));

        String prepared = service.prepareSession("shop-form", "https://shop.example.com");
        String response = service.validateSession(extractSessionId(prepared));

        assertThat(response).contains("Valid: false", "Outcome: authentication_failed");
        assertThat(response).doesNotContain("example-password-value", "scan-password.txt");
        assertRunbookDocuments("authentication_failed");
    }

    @Test
    void indeterminateFormValidationFailsClosed() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        stubFormUser("shop-form-auth", "1", "7");
        when(contextUserService.testUserAuthentication("1", "7"))
                .thenReturn(Map.of());

        String prepared = service.prepareSession("shop-form", "https://shop.example.com");
        String response = service.validateSession(extractSessionId(prepared));

        assertThat(response).contains(
                "Valid: false",
                "Outcome: authentication_failed",
                "likelyAuthenticated=null"
        );
        assertThat(response).doesNotContain("example-password-value", "scan-password.txt");
    }

    @Test
    void validationRejectsSessionWhoseStoredLoginEscapesProfileOrigin() throws Exception {
        Files.writeString(formPasswordFile(), "example-password-value", StandardCharsets.UTF_8);
        PreparedAuthSessionRegistry registry = new InMemoryPreparedAuthSessionRegistry();
        PreparedAuthSession session = registry.save(new PreparedAuthSession(
                "tampered",
                "shop-form",
                AuthBootstrapKind.FORM,
                "zap-form-login",
                new mcp.server.zap.core.gateway.TargetDescriptor(
                        mcp.server.zap.core.gateway.TargetDescriptor.Kind.WEB,
                        "https://shop.example.com",
                        "shop-form-auth"
                ),
                HttpOrigin.fromConfiguredOrigin("https://shop.example.com"),
                "file:" + formPasswordFile(),
                "shop-form-auth",
                "1",
                "zap-scan-user",
                "7",
                null,
                "https://attacker.example/login",
                true
        ));
        CredentialReferenceResolver credentialResolver = new CredentialReferenceResolver();
        service = new GuidedAuthSessionService(
                List.of(new FormLoginAuthBootstrapProvider(contextUserService, credentialResolver, urlValidationService)),
                registry,
                new AuthProfileResolver(propertiesWith(formProfile(
                        "shop-form",
                        "https://shop.example.com",
                        "file:" + formPasswordFile()
                )))
        );

        assertThatThrownBy(() -> service.validateSession(session.sessionId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auth session loginUrl origin is not authorized for auth profile");

        verifyNoInteractions(contextUserService);
    }

    @Test
    void runbookDocumentsCurrentFailureTaxonomy() throws Exception {
        String runbook = authRunbook();

        assertThat(runbook).contains(
                "Unknown auth profile ID",
                "targetUrl origin is not authorized for auth profile",
                "Auth profile credential could not be resolved",
                "authentication_failed",
                "Unknown auth session ID",
                "form-login prepare and validate",
                "`zap_crawl_start`",
                "`zap_attack_start`"
        );
        assertThat(runbook).doesNotContain(
                "top-secret-token",
                "StrongPassword123!",
                "`zap_crawl`",
                "`zap_attack`"
        );
    }

    @Test
    void migrationVerifierRequiresAffirmativeAuthenticationEvidence() throws Exception {
        String guide = Files.readString(Path.of(
                "docs/src/content/docs/scanning/authenticated-scanning-best-practices.md"));

        assertThat(guide).contains(
                "grep -F 'Valid: true'",
                "grep -F 'Outcome: authenticated'",
                "grep -F 'likelyAuthenticated=true'",
                "set -euo pipefail",
                "MCP_ZAP_IMAGE_TAG=sha-REPLACE_WITH_FULL_MAIN_COMMIT_SHA",
                "[[ \"$MCP_ZAP_IMAGE_TAG\" =~ ^sha-[0-9a-f]{40}$ ]]",
                "--set-string \"mcp.image.tag=$MCP_ZAP_IMAGE_TAG\"",
                "--show-only templates/mcp-deployment.yaml",
                "grep -E \"^[[:space:]]+image: \\\"[^\\\"]+:${MCP_ZAP_IMAGE_TAG}\\\"$\""
        ).doesNotContain("grep -Eq '^[[:space:]]+tag:");
    }

    private GuidedAuthSessionService serviceWith(AuthBootstrapProperties.Profile... profiles) {
        CredentialReferenceResolver credentialResolver = new CredentialReferenceResolver();
        return new GuidedAuthSessionService(
                List.of(
                        new FormLoginAuthBootstrapProvider(contextUserService, credentialResolver, urlValidationService),
                        new HeaderCredentialAuthBootstrapProvider(credentialResolver, urlValidationService)
                ),
                new InMemoryPreparedAuthSessionRegistry(),
                new AuthProfileResolver(propertiesWith(profiles))
        );
    }

    private AuthBootstrapProperties propertiesWith(AuthBootstrapProperties.Profile... profiles) {
        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setProfiles(List.of(profiles));
        return properties;
    }

    private AuthBootstrapProperties.Profile formProfile(String id, String allowedOrigin, String credentialReference) {
        AuthBootstrapProperties.Profile profile = new AuthBootstrapProperties.Profile();
        profile.setId(id);
        profile.setKind("form");
        profile.setAllowedOrigin(allowedOrigin);
        profile.setCredentialReference(credentialReference);
        profile.setLoginUrl(allowedOrigin + "/login");
        profile.setUsername("zap-scan-user");
        profile.setZapUserName("zap-scan-user");
        profile.setUsernameField("username");
        profile.setPasswordField("password");
        profile.setLoggedInIndicatorRegex(".*Logout.*");
        profile.setLoggedOutIndicatorRegex(".*Sign in.*");
        return profile;
    }

    private AuthBootstrapProperties.Profile headerProfile(String id, String allowedOrigin, String credentialReference) {
        AuthBootstrapProperties.Profile profile = new AuthBootstrapProperties.Profile();
        profile.setId(id);
        profile.setKind("api-key");
        profile.setAllowedOrigin(allowedOrigin);
        profile.setCredentialReference(credentialReference);
        return profile;
    }

    private void stubFormUser(String contextName, String contextId, String userId) {
        when(contextUserService.upsertContext(
                eq(contextName),
                anyList(),
                anyList(),
                eq(true)
        )).thenReturn(Map.of("contextId", contextId));
        when(contextUserService.configureContextAuthentication(
                eq(contextId),
                eq("formBasedAuthentication"),
                eq(authMethodConfig("https://shop.example.com/login", "username", "password")),
                eq(".*Logout.*"),
                eq(".*Sign in.*")
        )).thenReturn(Map.of("contextId", contextId));
        when(contextUserService.upsertUser(
                eq(contextId),
                eq("zap-scan-user"),
                startsWith("username=zap-scan-user&password="),
                eq(true)
        )).thenReturn(Map.of("userId", userId, "userName", "zap-scan-user"));
    }

    private String extractSessionId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Session ID: ")) {
                return line.substring("Session ID: ".length()).trim();
            }
        }
        throw new AssertionError("No session ID found in response: " + response);
    }

    private String authMethodConfig(String loginUrl, String usernameField, String passwordField) {
        String loginRequestData = usernameField + "={%username%}&" + passwordField + "={%password%}";
        return "loginUrl=" + urlEncode(loginUrl) + "&loginRequestData=" + urlEncode(loginRequestData);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Path formPasswordFile() {
        return tempDir.resolve("scan-password.txt");
    }

    private Path apiTokenFile() {
        return tempDir.resolve("api-token.txt");
    }

    private void assertRunbookDocuments(String expectedText) {
        assertThat(authRunbook()).contains(expectedText);
    }

    private String authRunbook() {
        try {
            return Files.readString(Path.of("docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md"));
        } catch (Exception e) {
            throw new AssertionError("Unable to read auth bootstrap failure runbook", e);
        }
    }
}
