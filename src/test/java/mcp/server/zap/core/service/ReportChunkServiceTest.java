package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EngineReportAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class ReportChunkServiceTest {
    @TempDir Path root;
    private Path workspace;
    private ReportService service;

    @BeforeEach
    void setup() throws Exception {
        workspace = root.resolve("workspaces/default-workspace");
        Files.createDirectories(workspace);
        service = new ReportService(mock(EngineReportAccess.class));
        ReflectionTestUtils.setField(service, "reportDirectory", root.toString());
    }

    private Path report(String content) throws Exception {
        Path file = workspace.resolve("report.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void reconstructsLargeReportExactlyIncludingEmojiBomAndCrLf() throws Exception {
        String original = "\uFEFF{\r\n\"data\":\"" + "x😀é".repeat(75000) + "\"}\r\n";
        Path file = report(original);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        StringBuilder recovered = new StringBuilder();
        Long offset = 0L;
        String hash = null;
        int pages = 0;
        do {
            ReportService.ReportChunk page = service.readReportChunk(file.toString(), offset, 17003, hash);
            assertThat(page.artifactSha256()).isEqualTo(expectedHash);
            recovered.append(page.content());
            hash = page.artifactSha256();
            offset = page.nextOffset();
            pages++;
        } while (offset != null);
        assertThat(pages).isGreaterThan(1);
        assertThat(recovered.toString()).isEqualTo(original);
    }

    @Test
    void singleCharacterPagesMakeProgressAcrossSupplementaryCharacters() throws Exception {
        Path file = report("😀A🚀");
        ReportService.ReportChunk first = service.readReportChunk(file.toString(), null, 1, null);
        assertThat(first.content()).isEqualTo("😀");
        assertThat(first.nextOffset()).isEqualTo(1);
        ReportService.ReportChunk second = service.readReportChunk(file.toString(), first.nextOffset(), 1, first.artifactSha256());
        assertThat(second.content()).isEqualTo("A");
        ReportService.ReportChunk third = service.readReportChunk(file.toString(), second.nextOffset(), 1, first.artifactSha256());
        assertThat(third.content()).isEqualTo("🚀");
        assertThat(third.endOfFile()).isTrue();
        assertThat(third.nextOffset()).isNull();
    }

    @Test
    void eofOffsetAndEmptyArtifactsReturnEmptyFinalPage() throws Exception {
        Path file = report("A😀");
        ReportService.ReportChunk eof = service.readReportChunk(file.toString(), 2L, 1, null);
        assertThat(eof.content()).isEmpty();
        assertThat(eof.endOfFile()).isTrue();
        assertThat(eof.nextOffset()).isNull();
        ReportService.ReportChunk empty = service.readReportChunk(report("").toString(), null, null, null);
        assertThat(empty.totalCharacters()).isZero();
        assertThat(empty.content()).isEmpty();
        assertThat(empty.endOfFile()).isTrue();
    }

    @Test
    void rejectsInvalidOffsetsBudgetsAndHashes() throws Exception {
        Path file = report("abc");
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), -1L, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), 4L, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), Long.MAX_VALUE, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), 0L, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), 0L, 1, "invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), 0L, 1, "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256 changed");
    }

    @Test
    void subsequentPageRejectsChangedFileEvenWithSameLength() throws Exception {
        Path file = report("abcdef");
        ReportService.ReportChunk page = service.readReportChunk(file.toString(), 0L, 2, null);
        Files.writeString(file, "abXYZf");
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), page.nextOffset(), 2, page.artifactSha256()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256 changed");
    }

    @Test
    void previewRetainsItsTextContractAndNeverSplitsAnEmoji() throws Exception {
        Path file = report("A😀B");
        assertThat(service.readReport(file.toString(), 2)).contains("Characters Returned: 1", "Truncated: yes")
                .endsWith("\n\nA");
        assertThat(service.readReport(file.toString(), 4)).contains("Characters Returned: 4", "Truncated: no")
                .endsWith("\n\nA😀B");
    }

    @Test
    void defaultPageAndMaximumBudgetAreBounded() throws Exception {
        Path file = report("x".repeat(210000));
        assertThat(service.readReportChunk(file.toString(), null, null, null).content().length()).isEqualTo(20000);
        assertThat(service.readReportChunk(file.toString(), null, Integer.MAX_VALUE, null).content().length()).isEqualTo(200000);
    }

    @Test
    void bothReadersRejectOutsideWorkspaceAndTraversal() throws Exception {
        Path other = root.resolve("workspaces/another-client/report.json");
        Files.createDirectories(other.getParent()); Files.writeString(other, "private");
        assertBothReadersReject(other.toString());
        assertBothReadersReject("../another-client/report.json");
    }

    @Test
    void bothReadersRejectLeafAndAncestorSymlinks() throws Exception {
        Path other = root.resolve("workspaces/another-client/private.json");
        Files.createDirectories(other.getParent()); Files.writeString(other, "private");
        Path leaf = workspace.resolve("leaf.json");
        try { Files.createSymbolicLink(leaf, other); }
        catch (UnsupportedOperationException exception) { assumeTrue(false, "Symbolic links unavailable"); }
        assertBothReadersReject(leaf.toString());
        Path directory = workspace.resolve("redirect");
        Files.createSymbolicLink(directory, other.getParent());
        assertBothReadersReject(directory.resolve("private.json").toString());
        Path current = root.resolve("workspaces/default-workspace");
        Files.delete(leaf); Files.delete(directory); Files.delete(current);
        Files.createSymbolicLink(current, other.getParent());
        assertBothReadersReject(current.resolve("private.json").toString());
    }

    @Test
    void bothReadersRejectMultipleHardLinksWhereSupported() throws Exception {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path original = root.resolve("private.txt"); Files.writeString(original, "private");
        Path link = workspace.resolve("linked.json"); Files.createLink(link, original);
        assertBothReadersReject(link.toString());
    }

    @Test
    void bothReadersRejectReservedGenerationStaging() throws Exception {
        Path file = workspace.resolve(".report-staging/raw.json");
        Files.createDirectories(file.getParent()); Files.writeString(file, "private");
        assertBothReadersReject(file.toString());
    }

    @Test
    void rejectsOversizedArtifactBeforeReading() throws Exception {
        Path file = workspace.resolve("huge.json");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(ReportService.MAX_REPORT_ARTIFACT_BYTES); channel.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertBothReadersReject(file.toString());
    }

    @Test
    void malformedUtf8FailsRatherThanReturningReplacementCharacters() throws Exception {
        Path file = workspace.resolve("invalid.json"); Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> service.readReportChunk(file.toString(), null, null, null)).isInstanceOf(ZapApiException.class);
    }

    private void assertBothReadersReject(String path) {
        assertThatThrownBy(() -> service.readReport(path, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readReportChunk(path, null, 100, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
