package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupValidatorTest {

    @Test
    void placeholderApiKeyIsAllowedWhenExplicitlyPermitted() {
        SecurityStartupValidator validator = validator("api-key", true, true, "changeme-default-key");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void placeholderApiKeyFailsFastWhenDisallowed() {
        SecurityStartupValidator validator = validator("api-key", true, false, "changeme-default-key");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Placeholder MCP API key detected");
    }

    @Test
    void blankApiKeyFailsWhenSecurityRequiresAuthentication() {
        SecurityStartupValidator validator = validator("jwt", true, false, "");

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires at least one API key");
    }

    @Test
    void noneModeSkipsApiKeyValidation() {
        SecurityStartupValidator validator = validator("none", true, false, "");

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    private SecurityStartupValidator validator(String securityMode,
                                               boolean securityEnabled,
                                               boolean allowPlaceholderApiKey,
                                               String apiKey) {
        ApiKeyProperties properties = new ApiKeyProperties();
        ApiKeyProperties.ApiKeyClient client = new ApiKeyProperties.ApiKeyClient();
        client.setClientId("test-client");
        client.setKey(apiKey);
        properties.getApiKeys().add(client);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        return new SecurityStartupValidator(
                properties,
                environment,
                securityMode,
                securityEnabled,
                allowPlaceholderApiKey,
                apiKey
        );
    }
}
