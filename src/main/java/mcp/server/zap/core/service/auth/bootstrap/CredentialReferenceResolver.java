package mcp.server.zap.core.service.auth.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the exact environment or file reference held by an operator-managed auth profile.
 */
@Component
@Slf4j
public class CredentialReferenceResolver {
    private static final String CALLER_FAILURE_MESSAGE = "Auth profile credential could not be resolved";

    public String resolveSecret(String credentialReference) {
        CredentialReference reference;
        try {
            reference = normalizeReference(credentialReference);
        } catch (RuntimeException e) {
            log.warn("Auth profile credential reference is invalid at runtime");
            throw callerFailure();
        }
        if ("env".equals(reference.scheme())) {
            String value;
            try {
                value = System.getenv(reference.envName());
            } catch (RuntimeException e) {
                log.error(
                        "Unable to access auth profile credential environment variable: {}",
                        reference.envName(),
                        e
                );
                throw callerFailure();
            }
            if (!hasText(value)) {
                log.warn(
                        "Auth profile credential environment variable is missing or blank: {}",
                        reference.envName()
                );
                throw callerFailure();
            }
            return value.trim();
        }

        try {
            String value = Files.readString(reference.filePath(), StandardCharsets.UTF_8).trim();
            if (!hasText(value)) {
                log.warn("Auth profile credential file is empty: {}", reference.filePath());
                throw callerFailure();
            }
            return value;
        } catch (IOException | SecurityException e) {
            log.error("Unable to read auth profile credential file: {}", reference.filePath(), e);
            throw callerFailure();
        }
    }

    private IllegalArgumentException callerFailure() {
        return new IllegalArgumentException(CALLER_FAILURE_MESSAGE);
    }

    static void validateReference(String credentialReference) {
        normalizeReference(credentialReference);
    }

    private static CredentialReference normalizeReference(String credentialReference) {
        if (!hasText(credentialReference)) {
            throw new IllegalArgumentException("Auth profile credentialReference cannot be null or blank");
        }
        String value = credentialReference.trim();
        if (value.startsWith("env:")) {
            String envName = value.substring("env:".length()).trim();
            if (!hasText(envName)) {
                throw new IllegalArgumentException("credentialReference env name cannot be blank");
            }
            return CredentialReference.env(envName);
        }
        if (value.startsWith("file:")) {
            String rawPath = value.substring("file:".length()).trim();
            if (!hasText(rawPath)) {
                throw new IllegalArgumentException("credentialReference file path cannot be blank");
            }
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("credentialReference file path must be absolute");
            }
            return CredentialReference.file(path.toAbsolutePath().normalize());
        }
        throw new IllegalArgumentException(
                "credentialReference must use env:NAME or file:/absolute/path syntax"
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record CredentialReference(String scheme, String envName, Path filePath) {
        private static CredentialReference env(String envName) {
            return new CredentialReference("env", envName, null);
        }

        private static CredentialReference file(Path filePath) {
            return new CredentialReference("file", null, filePath);
        }
    }
}
