package mcp.server.zap.core.service.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mcp.server.zap.core.configuration.AuthBootstrapProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialReferenceResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void fileWildcardAllowsChildPath() throws Exception {
        Path allowedDirectory = Files.createDirectories(tempDir.resolve("mcp-zap"));
        Path token = allowedDirectory.resolve("token");
        Files.writeString(token, "child-secret\n");

        CredentialReferenceResolver resolver = resolverAllowing("file:" + allowedDirectory + "/*");

        assertThat(resolver.resolveSecret("file:" + token, null)).isEqualTo("child-secret");
    }

    @Test
    void fileWildcardRejectsSiblingPrefixPath() throws Exception {
        Path allowedDirectory = Files.createDirectories(tempDir.resolve("mcp-zap"));
        Path siblingDirectory = Files.createDirectories(tempDir.resolve("mcp-zap-prod"));
        Path token = siblingDirectory.resolve("token");
        Files.writeString(token, "sibling-secret\n");

        CredentialReferenceResolver resolver = resolverAllowing("file:" + allowedDirectory + "/*");

        assertThatThrownBy(() -> resolver.resolveSecret("file:" + token, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credentialReference is not in the operator allowlist");
    }

    @Test
    void fileWildcardRejectsSymlinkEscape() throws Exception {
        Path allowedDirectory = Files.createDirectories(tempDir.resolve("mcp-zap"));
        Path outsideDirectory = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideToken = outsideDirectory.resolve("token");
        Files.writeString(outsideToken, "outside-secret\n");
        Path symlink = allowedDirectory.resolve("linked-token");
        try {
            Files.createSymbolicLink(symlink, outsideToken);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available on this filesystem");
        }

        CredentialReferenceResolver resolver = resolverAllowing("file:" + allowedDirectory + "/*");

        assertThatThrownBy(() -> resolver.resolveSecret("file:" + symlink, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("credentialReference is not in the operator allowlist");
    }

    private CredentialReferenceResolver resolverAllowing(String allowedReference) {
        AuthBootstrapProperties properties = new AuthBootstrapProperties();
        properties.setAllowedCredentialReferences(List.of(allowedReference));
        return new CredentialReferenceResolver(properties);
    }
}
