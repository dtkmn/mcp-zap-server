package mcp.server.zap.core.service.auth.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves credential references while keeping inline secrets disabled by default.
 */
@Component
public class CredentialReferenceResolver {
    private final AuthBootstrapProperties authBootstrapProperties;

    public CredentialReferenceResolver(AuthBootstrapProperties authBootstrapProperties) {
        this.authBootstrapProperties = authBootstrapProperties;
    }

    public String resolveSecret(String credentialReference, String inlineSecret) {
        if (hasText(credentialReference)) {
            return resolveReference(credentialReference.trim());
        }
        if (hasText(inlineSecret)) {
            if (!authBootstrapProperties.isAllowInlineSecrets()) {
                throw new IllegalArgumentException(
                        "inlineSecret is disabled by default. Use credentialReference (env:NAME or file:/abs/path) or explicitly enable mcp.server.auth.bootstrap.allow-inline-secrets for local workflows."
                );
            }
            return inlineSecret.trim();
        }
        throw new IllegalArgumentException("Provide credentialReference or inlineSecret");
    }

    private String resolveReference(String credentialReference) {
        if (credentialReference.startsWith("env:")) {
            String envName = credentialReference.substring("env:".length()).trim();
            if (!hasText(envName)) {
                throw new IllegalArgumentException("credentialReference env name cannot be blank");
            }
            String value = System.getenv(envName);
            if (!hasText(value)) {
                throw new IllegalArgumentException("Environment variable '" + envName + "' is missing or blank");
            }
            return value.trim();
        }

        if (credentialReference.startsWith("file:")) {
            String rawPath = credentialReference.substring("file:".length()).trim();
            if (!hasText(rawPath)) {
                throw new IllegalArgumentException("credentialReference file path cannot be blank");
            }
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("credentialReference file path must be absolute");
            }
            try {
                String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!hasText(value)) {
                    throw new IllegalArgumentException("Credential file '" + path + "' is empty");
                }
                return value;
            } catch (IOException e) {
                throw new IllegalArgumentException("Unable to read credentialReference file '" + path + "'", e);
            }
        }

        throw new IllegalArgumentException(
                "credentialReference must use env:NAME or file:/absolute/path syntax"
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
