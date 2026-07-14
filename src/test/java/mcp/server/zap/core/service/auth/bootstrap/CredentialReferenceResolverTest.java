package mcp.server.zap.core.service.auth.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CredentialReferenceResolverTest {
    @TempDir
    private Path tempDir;

    private final CredentialReferenceResolver resolver = new CredentialReferenceResolver();
    private Logger resolverLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        resolverLogger = (Logger) LoggerFactory.getLogger(CredentialReferenceResolver.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        resolverLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        resolverLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void resolvesExactFileReferenceFromOperatorProfile() throws Exception {
        Path token = tempDir.resolve("token");
        Files.writeString(token, "profile-secret\n");

        assertThat(resolver.resolveSecret("file:" + token)).isEqualTo("profile-secret");
    }

    @Test
    void sanitizesInvalidRuntimeReference() {
        assertThatThrownBy(() -> resolver.resolveSecret("file:relative/token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();
    }

    @Test
    void rejectsWildcardReferenceEvenWhenMatchingFileExists() throws Exception {
        Files.writeString(tempDir.resolve("matching-secret"), "secret");

        assertThatThrownBy(() -> resolver.resolveSecret("file:" + tempDir + "/*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();
    }

    @Test
    void missingEnvironmentIsDetailedOnlyInOperatorLogs() {
        String envName = "MCP_ZAP_AUTH_PROFILE_TEST_MISSING";

        assertThatThrownBy(() -> resolver.resolveSecret("env:" + envName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains(envName));
    }

    @Test
    void missingFileIsDetailedOnlyInOperatorLogs() {
        Path missingFile = tempDir.resolve("operator-only-path");

        assertThatThrownBy(() -> resolver.resolveSecret("file:" + missingFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Auth profile credential could not be resolved")
                .hasNoCause();

        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains(missingFile.toString()));
    }
}
