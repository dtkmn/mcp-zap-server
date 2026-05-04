package mcp.server.zap.core.service.auth.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            CredentialReference normalizedReference = normalizeAndAuthorizeReference(credentialReference.trim());
            return resolveReference(normalizedReference);
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

    private CredentialReference normalizeAndAuthorizeReference(String credentialReference) {
        CredentialReference normalizedReference = normalizeReference(credentialReference);
        if (!isAllowed(normalizedReference)) {
            throw new IllegalArgumentException(
                    "credentialReference is not in the operator allowlist"
            );
        }
        return normalizedReference;
    }

    private CredentialReference normalizeReference(String credentialReference) {
        if (credentialReference.startsWith("env:")) {
            String envName = credentialReference.substring("env:".length()).trim();
            if (!hasText(envName)) {
                throw new IllegalArgumentException("credentialReference env name cannot be blank");
            }
            return CredentialReference.env(envName);
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
            return CredentialReference.file(path.toAbsolutePath().normalize());
        }

        throw new IllegalArgumentException(
                "credentialReference must use env:NAME or file:/absolute/path syntax"
        );
    }

    private boolean isAllowed(CredentialReference normalizedReference) {
        List<String> allowedReferences = authBootstrapProperties.getAllowedCredentialReferences();
        if (allowedReferences == null || allowedReferences.isEmpty()) {
            return false;
        }

        return allowedReferences.stream()
                .filter(this::hasText)
                .map(String::trim)
                .map(this::normalizeAllowlistEntry)
                .anyMatch(allowed -> allowlistEntryMatches(allowed, normalizedReference));
    }

    private AllowlistEntry normalizeAllowlistEntry(String allowedReference) {
        if (allowedReference.endsWith("*")) {
            String prefix = allowedReference.substring(0, allowedReference.length() - 1);
            return normalizeReferencePrefix(prefix);
        }
        return AllowlistEntry.exact(normalizeReference(allowedReference));
    }

    private AllowlistEntry normalizeReferencePrefix(String prefix) {
        if (prefix.startsWith("env:")) {
            String envPrefix = prefix.substring("env:".length()).trim();
            if (!hasText(envPrefix)) {
                throw new IllegalArgumentException("credentialReference env name cannot be blank");
            }
            return AllowlistEntry.env(envPrefix, true);
        }
        if (prefix.startsWith("file:")) {
            String rawPath = prefix.substring("file:".length()).trim();
            if (!hasText(rawPath)) {
                throw new IllegalArgumentException("credentialReference file path cannot be blank");
            }
            Path path = Path.of(rawPath);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("credentialReference file path must be absolute");
            }
            return AllowlistEntry.file(path.toAbsolutePath().normalize(), true);
        }
        throw new IllegalArgumentException(
                "credentialReference must use env:NAME or file:/absolute/path syntax"
        );
    }

    private boolean allowlistEntryMatches(AllowlistEntry allowedReference, CredentialReference normalizedReference) {
        if (!allowedReference.scheme().equals(normalizedReference.scheme())) {
            return false;
        }

        if ("env".equals(normalizedReference.scheme())) {
            return allowedReference.wildcard()
                    ? normalizedReference.envName().startsWith(allowedReference.envName())
                    : normalizedReference.envName().equals(allowedReference.envName());
        }

        if (!allowedReference.wildcard()) {
            return sameFileOrNormalized(normalizedReference.filePath(), allowedReference.filePath());
        }
        return fileIsInsideDirectory(normalizedReference.filePath(), allowedReference.filePath());
    }

    private boolean sameFileOrNormalized(Path candidate, Path allowedPath) {
        return pathForComparison(candidate).equals(pathForComparison(allowedPath));
    }

    private boolean fileIsInsideDirectory(Path candidate, Path allowedDirectory) {
        Path candidatePath = pathForComparison(candidate);
        Path directoryPath = pathForComparison(allowedDirectory);
        return !candidatePath.equals(directoryPath) && candidatePath.startsWith(directoryPath);
    }

    private Path pathForComparison(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return bestEffortRealPath(path);
        }
    }

    private Path bestEffortRealPath(Path path) {
        Path absolutePath = path.toAbsolutePath().normalize();
        Path nearestExistingPath = absolutePath;
        List<Path> missingSegments = new ArrayList<>();

        while (nearestExistingPath != null && !Files.exists(nearestExistingPath)) {
            Path fileName = nearestExistingPath.getFileName();
            if (fileName != null) {
                missingSegments.add(0, fileName);
            }
            nearestExistingPath = nearestExistingPath.getParent();
        }

        if (nearestExistingPath == null) {
            return absolutePath;
        }

        try {
            Path resolvedPath = nearestExistingPath.toRealPath();
            for (Path missingSegment : missingSegments) {
                resolvedPath = resolvedPath.resolve(missingSegment.toString());
            }
            return resolvedPath.normalize();
        } catch (IOException ignored) {
            return absolutePath;
        }
    }

    private String resolveReference(CredentialReference credentialReference) {
        if ("env".equals(credentialReference.scheme())) {
            String envName = credentialReference.envName();
            String value = System.getenv(envName);
            if (!hasText(value)) {
                throw new IllegalArgumentException("Environment variable '" + envName + "' is missing or blank");
            }
            return value.trim();
        }

        if ("file".equals(credentialReference.scheme())) {
            Path path = credentialReference.filePath();
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

        throw new IllegalArgumentException("credentialReference is not in the operator allowlist");
    }

    private boolean hasText(String value) {
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

    private record AllowlistEntry(String scheme, String envName, Path filePath, boolean wildcard) {
        private static AllowlistEntry exact(CredentialReference reference) {
            return new AllowlistEntry(reference.scheme(), reference.envName(), reference.filePath(), false);
        }

        private static AllowlistEntry env(String envName, boolean wildcard) {
            return new AllowlistEntry("env", envName, null, wildcard);
        }

        private static AllowlistEntry file(Path filePath, boolean wildcard) {
            return new AllowlistEntry("file", null, filePath, wildcard);
        }
    }
}
