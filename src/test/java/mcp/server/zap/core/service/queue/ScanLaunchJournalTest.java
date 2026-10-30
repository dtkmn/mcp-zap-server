package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanLaunchJournalTest {
    @TempDir
    Path temporaryDirectory;

    private Path directory;
    private List<Path> artifactRoots;

    @BeforeEach
    void isolateCanonicalPaths() throws IOException {
        Path root = temporaryDirectory.toRealPath();
        directory = root.resolve("private-launch-state");
        artifactRoots = List.of(root.resolve("reports"), root.resolve("automation"));
    }

    @Test
    void restartPreservesOutstandingReceiptAndFencesAdmission() throws Exception {
        ScanLaunchJournal.Receipt receipt = receipt("job-1", "attempt-1", 0, "native-42");
        try (ScanLaunchJournal journal = open()) {
            assertFalse(journal.admissionFenced());
            assertDoesNotThrow(journal::requireNoOrphans);
            journal.record(receipt);
            assertFalse(journal.admissionFenced(), "The current controller still owns its receipt");
        }

        try (ScanLaunchJournal recovered = open()) {
            assertTrue(recovered.admissionFenced());
            assertEquals(List.of(receipt), recovered.orphanedReceipts());
            assertThrows(IllegalStateException.class, recovered::requireNoOrphans);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyFormatOneReceiptRetainsOwnershipAndRewritesWithoutUnusedLabel(boolean hasNativeId) throws Exception {
        Files.createDirectories(directory);
        Properties legacy = legacyReceipt(hasNativeId);
        signFixture(legacy);
        writeProperties(legacy);
        ScanLaunchJournal.Receipt expected = receipt("legacy-job", "legacy-attempt", 7,
                hasNativeId ? "native-legacy" : null);

        try (ScanLaunchJournal recovered = open()) {
            assertTrue(recovered.admissionFenced());
            assertEquals(List.of(expected), recovered.orphanedReceipts());
            assertThrows(IllegalStateException.class, recovered::requireNoOrphans);
            assertFalse(readState().containsKey("0.incarnation"));
        }
        try (ScanLaunchJournal reopened = open()) {
            assertTrue(reopened.admissionFenced());
            assertEquals(List.of(expected), reopened.orphanedReceipts());
        }
    }

    @Test
    void legacyLabelStillParticipatesInChecksumBeforeItIsIgnored() throws Exception {
        Files.createDirectories(directory);
        Properties legacy = legacyReceipt(true);
        signFixture(legacy);
        legacy.setProperty("0.incarnation", "tampered-unused-label");
        writeProperties(legacy);
        byte[] original = Files.readAllBytes(stateFile());
        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(original, Files.readAllBytes(stateFile()));
    }

    private Properties legacyReceipt(boolean hasNativeId) {
        // Literal legacy envelope: do not generate this fixture through the current model writer.
        Properties legacy = new Properties();
        legacy.setProperty("format", "1");
        legacy.setProperty("count", "1");
        legacy.setProperty("0.job", "legacy-job");
        legacy.setProperty("0.type", "ACTIVE_SCAN");
        legacy.setProperty("0.requester", "scan-client");
        legacy.setProperty("0.attempt", "legacy-attempt");
        legacy.setProperty("0.controller", "original-controller");
        legacy.setProperty("0.incarnation", "operator-managed-old-label");
        legacy.setProperty("0.time", "2026-01-01T00:00:00Z");
        legacy.setProperty("0.version", "7");
        if (hasNativeId) legacy.setProperty("0.scan", "native-legacy");
        return legacy;
    }

    @Test
    void attachingOrphanResolvesCurrentHoldButRetainsReceiptForAnotherRestart() throws Exception {
        ScanLaunchJournal.Receipt receipt = receipt("job-1", "attempt-1", 0, null);
        recordAndClose(receipt);
        ScanLaunchJournal.Receipt attached = new ScanLaunchJournal.Receipt(receipt.jobId(), receipt.type(),
                receipt.requesterId(), receipt.attempt().resolvedBy("replacement-controller"), "native-42");

        try (ScanLaunchJournal recovered = open()) {
            recovered.resolved(attached);
            assertFalse(recovered.admissionFenced());
            assertTrue(recovered.orphanedReceipts().isEmpty());
        }

        try (ScanLaunchJournal recoveredAgain = open()) {
            assertTrue(recoveredAgain.admissionFenced());
            assertEquals(List.of(attached), recoveredAgain.orphanedReceipts());
            recoveredAgain.remove(attached.jobId(), attached.attempt().id());
            assertFalse(recoveredAgain.admissionFenced());
        }

        try (ScanLaunchJournal retired = open()) {
            assertFalse(retired.admissionFenced());
            assertTrue(retired.orphanedReceipts().isEmpty());
        }
    }

    @Test
    void differentAttemptCannotRemoveOrReplaceOutstandingReceipt() throws Exception {
        ScanLaunchJournal.Receipt receipt = receipt("job-1", "attempt-1", 1, "native-42");
        recordAndClose(receipt);

        try (ScanLaunchJournal recovered = open()) {
            recovered.remove(receipt.jobId(), "different-attempt");
            assertTrue(recovered.admissionFenced());
            assertThrows(IllegalStateException.class,
                    () -> recovered.record(receipt("job-1", "different-attempt", 2, "native-99")));
            assertThrows(IllegalStateException.class,
                    () -> recovered.record(receipt("job-1", "attempt-1", 0, "native-99")));
        }

        try (ScanLaunchJournal recoveredAgain = open()) {
            assertEquals(List.of(receipt), recoveredAgain.orphanedReceipts());
        }
    }

    @Test
    void unknownOutcomeWriteCannotEraseAlreadyRecordedNativeId() throws Exception {
        ScanLaunchJournal.Receipt accepted = receipt("job-1", "attempt-1", 0, "native-42");
        try (ScanLaunchJournal journal = open()) {
            journal.record(accepted);
            journal.record(receipt("job-1", "attempt-1", 0, null));
        }

        try (ScanLaunchJournal recovered = open()) {
            assertEquals(List.of(accepted), recovered.orphanedReceipts());
        }
    }

    @Test
    void onlyOneControllerCanOpenJournalUntilOwnerClosesIt() throws Exception {
        try (ScanLaunchJournal owner = open()) {
            assertThrows(IllegalStateException.class, this::open);
            assertFalse(owner.admissionFenced());
        }

        try (ScanLaunchJournal replacement = open()) {
            assertFalse(replacement.admissionFenced());
        }
    }

    @Test
    void missingReceiptFileAfterInitializationIsNotTreatedAsFreshState() throws Exception {
        recordAndClose(receipt("job-1", "attempt-1", 0, "native-42"));
        Files.delete(stateFile());

        assertThrows(IllegalStateException.class, this::open);
        assertTrue(Files.exists(directory.resolve("controller.lock")));
        assertFalse(Files.exists(stateFile()), "Failed recovery must not overwrite the lost-state evidence");
    }

    @Test
    void checksumCorruptionFailsClosedWithoutRewritingEvidence() throws Exception {
        recordAndClose(receipt("job-1", "attempt-1", 0, "native-42"));
        Properties properties = readState();
        properties.setProperty("0.scan", "native-99");
        writeProperties(properties);
        byte[] corrupted = Files.readAllBytes(stateFile());

        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(corrupted, Files.readAllBytes(stateFile()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"understated-count", "extra-field", "missing-field", "duplicate-job"})
    void semanticallyCorruptEnvelopeFailsClosedEvenWithValidChecksum(String corruption) throws Exception {
        ScanLaunchJournal.Receipt receipt = receipt("job-1", "attempt-1", 0, "native-42");
        recordAndClose(receipt);
        Properties properties = readState();
        signFixture(properties);
        writeProperties(properties);
        try (ScanLaunchJournal control = open()) {
            assertEquals(List.of(receipt), control.orphanedReceipts(),
                    "The fixture signer must produce readable state before semantic corruption is introduced");
        }
        properties = readState();
        switch (corruption) {
            case "understated-count" -> properties.setProperty("count", "0");
            case "extra-field" -> properties.setProperty("unexpected", "not-a-receipt-field");
            case "missing-field" -> properties.remove("0.requester");
            case "duplicate-job" -> {
                for (String key : List.copyOf(properties.stringPropertyNames())) {
                    if (key.startsWith("0.")) properties.setProperty("1." + key.substring(2), properties.getProperty(key));
                }
                properties.setProperty("count", "2");
            }
            default -> throw new IllegalArgumentException(corruption);
        }
        signFixture(properties);
        writeProperties(properties);
        byte[] corrupted = Files.readAllBytes(stateFile());

        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(corrupted, Files.readAllBytes(stateFile()));
    }

    @Test
    void duplicatePropertyCannotHideBehindAnOtherwiseValidReceipt() throws Exception {
        recordAndClose(receipt("job-1", "attempt-1", 0, "native-42"));
        String valid = Files.readString(stateFile(), StandardCharsets.ISO_8859_1);
        Files.writeString(stateFile(), valid + "\ncount=1\n", StandardCharsets.ISO_8859_1);
        byte[] corrupted = Files.readAllBytes(stateFile());

        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(corrupted, Files.readAllBytes(stateFile()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "descendant", "ancestor"})
    void overlappingArtifactPathsAreRejectedBeforeDirectoriesOrPermissionsChange(String relation) throws Exception {
        Path base = Files.createDirectory(temporaryDirectory.toRealPath().resolve(relation));
        Path reports = Files.createDirectory(base.resolve("reports"));
        Path automation = base.resolve("automation");
        var originalPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Files.setPosixFilePermissions(base, originalPermissions);
        Files.setPosixFilePermissions(reports, originalPermissions);
        Path overlapping = switch (relation) {
            case "same" -> reports;
            case "descendant" -> reports.resolve("journal");
            case "ancestor" -> base;
            default -> throw new IllegalArgumentException(relation);
        };

        assertThrows(IllegalStateException.class,
                () -> new ScanLaunchJournal(overlapping, List.of(reports, automation)));
        assertEquals(originalPermissions, Files.getPosixFilePermissions(base));
        assertEquals(originalPermissions, Files.getPosixFilePermissions(reports));
        assertFalse(Files.exists(overlapping.resolve("controller.lock")));
        assertFalse(Files.exists(overlapping.resolve("launches.properties")));
        if (relation.equals("descendant")) assertFalse(Files.exists(overlapping));
    }

    @Test
    void symlinkedDirectoryIsRejectedWithoutTouchingItsTarget() throws Exception {
        Path target = Files.createDirectory(temporaryDirectory.toRealPath().resolve("linked-target"));
        var originalPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Files.setPosixFilePermissions(target, originalPermissions);
        Files.createSymbolicLink(directory, target);

        assertThrows(IllegalStateException.class, this::open);
        assertEquals(originalPermissions, Files.getPosixFilePermissions(target));
        try (var children = Files.list(target)) {
            assertEquals(0, children.count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"state-symlink", "state-hardlink", "lock-symlink", "lock-hardlink"})
    void linkedStateOrLockFilesAreRejectedWithoutModifyingTarget(String linkType) throws Exception {
        recordAndClose(receipt("job-1", "attempt-1", 0, "native-42"));
        Path linked = linkType.startsWith("state") ? stateFile() : directory.resolve("controller.lock");
        Path target = temporaryDirectory.toRealPath().resolve("linked-file-target");
        if (linkType.endsWith("symlink")) {
            Files.move(linked, target);
            Files.createSymbolicLink(linked, target);
        } else {
            Files.createLink(target, linked);
        }
        byte[] original = Files.readAllBytes(target);

        assertThrows(IllegalStateException.class, this::open);
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test
    void persistenceFailurePermanentlyFencesCurrentControllerWithoutWritingLinkedTarget() throws Exception {
        Path target = temporaryDirectory.toRealPath().resolve("unrelated-target");
        byte[] original = "unrelated private contents".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        try (ScanLaunchJournal journal = open()) {
            Files.delete(stateFile());
            Files.createSymbolicLink(stateFile(), target);

            assertThrows(IllegalStateException.class,
                    () -> journal.record(receipt("job-1", "attempt-1", 0, "native-42")));
            assertTrue(journal.admissionFenced());
            assertThrows(IllegalStateException.class, journal::requireNoOrphans);
            Files.delete(stateFile());
            assertThrows(IllegalStateException.class,
                    () -> journal.record(receipt("job-2", "attempt-2", 0, null)));
            assertFalse(Files.exists(stateFile()), "Fixing a path must not silently reset failed ownership");
        }
        assertArrayEquals(original, Files.readAllBytes(target));
    }

    @Test
    void createdJournalFilesArePrivateAndAtomicWriteLeavesNoTemporaryFiles() throws Exception {
        try (ScanLaunchJournal journal = open()) {
            journal.record(receipt("job-1", "attempt-1", 0, "native-42"));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stateFile()));
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(directory.resolve("controller.lock")));
            try (var children = Files.list(directory)) {
                assertEquals(List.of("controller.lock", "launches.properties"),
                        children.map(path -> path.getFileName().toString()).sorted().toList());
            }
        }
    }

    private ScanLaunchJournal open() {
        return new ScanLaunchJournal(directory, artifactRoots);
    }

    private Path stateFile() {
        return directory.resolve("launches.properties");
    }

    private ScanLaunchJournal.Receipt receipt(String jobId, String attemptId, long version, String scanId) {
        return new ScanLaunchJournal.Receipt(jobId, ScanJobType.ACTIVE_SCAN, "scan-client",
                new ScanJob.LaunchAttempt(attemptId, "original-controller",
                        Instant.parse("2026-01-01T00:00:00Z"), version), scanId);
    }

    private void recordAndClose(ScanLaunchJournal.Receipt receipt) throws Exception {
        try (ScanLaunchJournal journal = open()) {
            journal.record(receipt);
        }
    }

    private Properties readState() throws IOException {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(Files.readAllBytes(stateFile())));
        return properties;
    }

    private void writeProperties(Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "Fault fixture");
        Files.write(stateFile(), output.toByteArray());
    }

    /** Forge a checksum-valid malformed envelope so validation cannot rely on checksum failure alone. */
    private void signFixture(Properties properties) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (String key : properties.stringPropertyNames().stream().filter(key -> !key.equals("checksum")).sorted().toList()) {
            String value = properties.getProperty(key);
            canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value).append('\n');
        }
        properties.setProperty("checksum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8))));
    }
}
