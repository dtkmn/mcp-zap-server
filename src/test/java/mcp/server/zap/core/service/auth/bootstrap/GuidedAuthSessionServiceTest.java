package mcp.server.zap.core.service.auth.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import mcp.server.zap.core.service.ContextUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuidedAuthSessionServiceTest {

    @TempDir
    Path tempDir;

    private ContextUserService contextUserService;
    private GuidedAuthSessionService service;

    @BeforeEach
    void setUp() {
        contextUserService = mock(ContextUserService.class);

        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setAllowInlineSecrets(false);

        CredentialReferenceResolver credentialReferenceResolver = new CredentialReferenceResolver(properties);
        PreparedAuthSessionRegistry registry = new InMemoryPreparedAuthSessionRegistry();

        service = new GuidedAuthSessionService(
                List.of(
                        new FormLoginAuthBootstrapProvider(contextUserService, credentialReferenceResolver),
                        new HeaderCredentialAuthBootstrapProvider(credentialReferenceResolver)
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
                eq(List.of("https://shop.example.com/.*")),
                eq(List.of("https://shop.example.com/logout.*")),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq("loginUrl=https://shop.example.com/login&loginRequestData=username={%username%}&password={%password%}"),
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

        String sessionId = extractSessionId(prepareResponse);
        String validateResponse = service.validateSession(sessionId);

        assertThat(validateResponse).contains("Valid: true");
        assertThat(validateResponse).contains("Outcome: reference_valid");
        assertThat(validateResponse).contains("Current guided ZAP flows do not automatically inject header-based auth yet.");
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
        String envName = "ASG_AUTH_BOOTSTRAP_TEST_MISSING_ENV";

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
                "env:ASG_AUTH_BOOTSTRAP_TEST_MISSING_ENV",
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
                eq(List.of("https://shop.example.com/.*")),
                eq(List.of("https://shop.example.com/logout.*")),
                eq(true)
        )).thenReturn(Map.of("contextId", "1"));
        when(contextUserService.configureContextAuthentication(
                eq("1"),
                eq("formBasedAuthentication"),
                eq("loginUrl=https://shop.example.com/login&loginRequestData=username={%username%}&password={%password%}"),
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

    private String authRunbook() {
        try {
            return Files.readString(Path.of("docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md"));
        } catch (Exception e) {
            throw new AssertionError("Unable to read auth bootstrap failure runbook", e);
        }
    }
}
