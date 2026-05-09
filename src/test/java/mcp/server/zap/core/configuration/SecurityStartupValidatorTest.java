package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupValidatorTest {

    @Test
    void placeholderApiKeyIsAllowedWhenExplicitlyPermitted() {
        SecurityStartupValidator validator = validator("api-key", true, true, "changeme-default-key", false, "");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void placeholderApiKeyFailsFastWhenDisallowed() {
        SecurityStartupValidator validator = validator("api-key", true, false, "changeme-default-key", false, "");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Placeholder MCP API key detected");
    }

    @Test
    void blankApiKeyFailsWhenSecurityRequiresAuthentication() {
        SecurityStartupValidator validator = validator("jwt", true, false, "", true, "a-secure-jwt-secret-with-at-least-32chars");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires at least one API key");
    }

    @Test
    void noneModeSkipsApiKeyValidation() {
        SecurityStartupValidator validator = validator("none", true, false, "", false, "");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void jwtModeRequiresJwtEnabledFlag() {
        SecurityStartupValidator validator = validator("jwt", true, false, "real-api-key", false, "a-secure-jwt-secret-with-at-least-32chars");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires JWT_ENABLED=true");
    }

    @Test
    void jwtModeRejectsBlankJwtSecret() {
        SecurityStartupValidator validator = validator("jwt", true, false, "real-api-key", true, "");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a non-empty JWT secret");
    }

    @Test
    void jwtModeRejectsPlaceholderJwtSecret() {
        SecurityStartupValidator validator = validator("jwt", true, false, "real-api-key", true, "jwt-secret-key-please-change-this-to-a-secure-random-value");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Placeholder JWT secret detected");
    }

    @Test
    void jwtModeRejectsShortJwtSecret() {
        SecurityStartupValidator validator = validator("jwt", true, false, "real-api-key", true, "short-jwt-secret");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be at least 32 characters");
    }

    @Test
    void jwtModeAcceptsValidJwtConfiguration() {
        SecurityStartupValidator validator = validator("jwt", true, false, "real-api-key", true, "a-secure-jwt-secret-with-at-least-32chars");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    private SecurityStartupValidator validator(String securityMode,
                                               boolean securityEnabled,
                                               boolean allowPlaceholderApiKey,
                                               String apiKey,
                                               boolean jwtEnabled,
                                               String jwtSecret) {
        ApiKeyProperties properties = new ApiKeyProperties();
        ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
        client.setClientId("test-client");
        client.setKey(apiKey);
        properties.setApiKeys(java.util.List.of(client));

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        return new SecurityStartupValidator(
                properties,
                environment,
                securityMode,
                securityEnabled,
                allowPlaceholderApiKey,
                apiKey,
                jwtEnabled,
                jwtSecret
        );
    }
}
