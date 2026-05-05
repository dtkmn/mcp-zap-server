package mcp.server.zap.core.configuration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Enforces a safer default posture for networked deployments while still allowing
 * explicit local/dev overrides to stay lightweight.
 */
@Component
public class SecurityStartupValidator implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);

    private static final Set<String> PLACEHOLDER_API_KEYS = Set.of(
            "changeme-default-key",
            "changeme-mcp-api-key",
            "your-secure-mcp-api-key-here",
            "__REPLACE_MCP_API_KEY__"
    );

    private static final Set<String> PLACEHOLDER_JWT_SECRETS = Set.of(
            "jwt-secret-key-please-change-this-to-a-secure-random-value",
            "your-jwt-secret-key-at-least-32-bytes-here",
            "__REPLACE_JWT_SECRET__"
    );

    private final ApiKeyProperties apiKeyProperties;
    private final Environment environment;
    private final String securityModeConfig;
    private final boolean securityEnabled;
    private final boolean allowPlaceholderApiKey;
    private final String legacyMcpApiKey;
    private final boolean jwtEnabled;
    private final String jwtSecret;

    public SecurityStartupValidator(ApiKeyProperties apiKeyProperties,
                                    Environment environment,
                                    @Value("${mcp.server.security.mode:api-key}") String securityModeConfig,
                                    @Value("${mcp.server.security.enabled:true}") boolean securityEnabled,
                                    @Value("${mcp.server.security.allowPlaceholderApiKey:false}") boolean allowPlaceholderApiKey,
                                    @Value("${mcp.server.apiKey:}") String legacyMcpApiKey,
                                    @Value("${mcp.server.auth.jwt.enabled:false}") boolean jwtEnabled,
                                    @Value("${mcp.server.auth.jwt.secret:}") String jwtSecret) {
        this.apiKeyProperties = apiKeyProperties;
        this.environment = environment;
        this.securityModeConfig = securityModeConfig;
        this.securityEnabled = securityEnabled;
        this.allowPlaceholderApiKey = allowPlaceholderApiKey;
        this.legacyMcpApiKey = legacyMcpApiKey;
        this.jwtEnabled = jwtEnabled;
        this.jwtSecret = jwtSecret;
    }

    @Override
    public void afterPropertiesSet() {
        SecurityConfig.SecurityMode mode = resolveMode();
        if (!securityEnabled || mode == SecurityConfig.SecurityMode.NONE) {
            return;
        }

        if (mode != SecurityConfig.SecurityMode.API_KEY && mode != SecurityConfig.SecurityMode.JWT) {
            return;
        }

        List<String> configuredKeys = Stream.concat(
                        apiKeyProperties.getApiKeys().stream().map(ApiKeyProperties.ApiKeyClient::getKey),
                        Stream.of(legacyMcpApiKey)
                )
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList();

        if (configuredKeys.isEmpty()) {
            throw new IllegalStateException(
                    "MCP security mode '" + securityModeConfig + "' requires at least one API key. "
                            + "Set MCP_API_KEY or mcp.server.auth.apiKeys[].key."
            );
        }

        List<String> placeholderKeys = configuredKeys.stream()
                .filter(this::isPlaceholderApiKey)
                .toList();

        if (placeholderKeys.isEmpty()) {
            validateJwtConfiguration(mode);
            return;
        }

        String activeProfiles = Arrays.toString(environment.getActiveProfiles());
        String message = "Placeholder MCP API key detected while security mode '" + mode.name().toLowerCase()
                + "' is enabled. Replace MCP_API_KEY / mcp.server.auth.apiKeys[].key with a real secret "
                + "before exposing this service. Active profiles=" + activeProfiles;

        if (allowPlaceholderApiKey) {
            log.warn(message);
            log.warn("Placeholder API keys are allowed only for local/dev convenience in this environment.");
            validateJwtConfiguration(mode);
            return;
        }

        throw new IllegalStateException(message + ". Placeholder API keys are disallowed in this environment.");
    }

    private void validateJwtConfiguration(SecurityConfig.SecurityMode mode) {
        if (mode != SecurityConfig.SecurityMode.JWT) {
            return;
        }

        if (!jwtEnabled) {
            throw new IllegalStateException(
                    "MCP security mode 'jwt' requires JWT_ENABLED=true."
            );
        }

        String configuredJwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
        if (configuredJwtSecret.isEmpty()) {
            throw new IllegalStateException(
                    "MCP security mode 'jwt' requires a non-empty JWT secret. Set JWT_SECRET or mcp.server.auth.jwt.secret."
            );
        }

        if (PLACEHOLDER_JWT_SECRETS.contains(configuredJwtSecret)) {
            throw new IllegalStateException(
                    "Placeholder JWT secret detected while JWT security mode is enabled. Replace JWT_SECRET / mcp.server.auth.jwt.secret with a real secret before exposing this service."
            );
        }

        if (configuredJwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 characters when JWT security mode is enabled."
            );
        }
    }

    private SecurityConfig.SecurityMode resolveMode() {
        try {
            return SecurityConfig.SecurityMode.valueOf(securityModeConfig.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid security mode '{}' during startup validation, treating it as API_KEY", securityModeConfig);
            return SecurityConfig.SecurityMode.API_KEY;
        }
    }

    private boolean isPlaceholderApiKey(String apiKey) {
        return PLACEHOLDER_API_KEYS.contains(apiKey);
    }
}
