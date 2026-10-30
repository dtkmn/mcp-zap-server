package mcp.server.zap.core.service.queue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobType;

/** Outstanding launch receipts only; this is a restart safety fence, not a second job queue. */
public final class ScanLaunchJournal implements AutoCloseable {
    public record Receipt(String jobId, ScanJobType type, String requesterId,
                          ScanJob.LaunchAttempt attempt, String scanId) { }

    private final Path directory;
    private final Path stateFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final Set<String> orphaned = new HashSet<>();
    private boolean failed;

    public ScanLaunchJournal(Path directory, List<Path> artifactRoots) {
        if (!directory.isAbsolute()) throw new IllegalArgumentException("Launch journal directory must be absolute");
        this.directory = directory.normalize();
        stateFile = this.directory.resolve("launches.properties");
        FileChannel opened = null;
        FileLock acquired = null;
        try {
            rejectSymlinks(this.directory);
            Path real = canonicalCandidate(this.directory);
            for (Path root : artifactRoots) {
                Path normalized = root.toAbsolutePath().normalize();
                rejectSymlinks(normalized);
                normalized = canonicalCandidate(normalized);
                if (real.startsWith(normalized) || normalized.startsWith(real)) {
                    throw new IOException("Launch journal must be separate from report and automation roots");
                }
            }
            Files.createDirectories(this.directory);
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
            Path lockFile = this.directory.resolve("controller.lock");
            rejectUnsafeFile(lockFile);
            boolean initialized = Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS);
            opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(lockFile, PosixFilePermissions.fromString("rw-------"));
            acquired = opened.tryLock();
            if (acquired == null) throw new IOException("Launch journal is owned by another controller");
            if (initialized && !Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Initialized launch journal lost its receipt file; operator recovery is required");
            }
            load();
            orphaned.addAll(receipts.keySet());
            // Establish writeability before any native effect, including an initially empty journal.
            persist();
        } catch (Exception e) {
            try { if (acquired != null) acquired.close(); } catch (IOException ignored) { }
            try { if (opened != null) opened.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("Launch journal is unavailable; queued native starts are fenced", e);
        }
        lockChannel = opened;
        lock = acquired;
    }

    public synchronized List<Receipt> orphanedReceipts() {
        return orphaned.stream().map(receipts::get).toList();
    }

    public synchronized void requireNoOrphans() {
        if (failed || !orphaned.isEmpty()) {
            throw new IllegalStateException("Unresolved launch journal receipts require operator reconciliation");
        }
    }

    public synchronized boolean admissionFenced() { return failed || !orphaned.isEmpty(); }

    public synchronized void record(Receipt receipt) {
        if (failed) throw new IllegalStateException("Launch journal is fenced after a persistence failure");
        Receipt prior = receipts.get(receipt.jobId());
        if (prior != null && (!prior.attempt().id().equals(receipt.attempt().id())
                || prior.attempt().resolutionVersion() > receipt.attempt().resolutionVersion())) {
            throw new IllegalStateException("A stale receipt cannot replace a newer launch fence");
        }
        if (prior != null && prior.scanId() != null && receipt.scanId() == null) {
            receipt = new Receipt(receipt.jobId(), receipt.type(), receipt.requesterId(), receipt.attempt(), prior.scanId());
        }
        if (prior == null && receipts.size() >= 1000) throw new IllegalStateException("Launch journal capacity is full");
        receipts.put(receipt.jobId(), receipt);
        writeOrFence();
    }

    public synchronized void resolved(Receipt receipt) {
        record(receipt);
        orphaned.remove(receipt.jobId());
    }

    public synchronized void remove(String jobId, String attemptId) {
        Receipt current = receipts.get(jobId);
        if (current != null && current.attempt().id().equals(attemptId)) {
            receipts.remove(jobId);
            writeOrFence();
            orphaned.remove(jobId);
        }
    }

    private void writeOrFence() {
        try { persist(); }
        catch (IOException e) {
            failed = true;
            throw new IllegalStateException("Launch journal persistence failed; native starts remain fenced", e);
        }
    }

    private void load() throws IOException {
        rejectUnsafeFile(stateFile);
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.size(stateFile) > 1_048_576) throw new IOException("Launch journal exceeds its bound");
        Properties data = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate launch journal key");
                return super.put(key, value);
            }
        };
        data.load(new ByteArrayInputStream(Files.readAllBytes(stateFile)));
        if (!"1".equals(data.getProperty("format"))) throw new IOException("Unknown launch journal format");
        if (!checksum(data).equals(required(data, "checksum"))) throw new IOException("Launch journal checksum does not match");
        try {
            int count = Integer.parseInt(data.getProperty("count"));
            if (count < 0 || count > 1000) throw new IllegalArgumentException("Invalid receipt count");
            Set<String> expected = new HashSet<>(Set.of("format", "count", "checksum"));
            for (int i = 0; i < count; i++) {
                for (String field : List.of("attempt", "controller", "time", "version", "job", "type", "requester")) {
                    expected.add(i + "." + field);
                }
                // Format 1 previously included an unused incarnation label. Its original
                // value remains covered by the checksum above, but is no longer retained.
                if (data.containsKey(i + ".incarnation")) expected.add(i + ".incarnation");
                if (data.containsKey(i + ".scan")) expected.add(i + ".scan");
            }
            if (!expected.equals(data.stringPropertyNames())) throw new IllegalArgumentException("Unexpected or missing journal fields");
            for (int i = 0; i < count; i++) {
                String key = i + ".";
                ScanJob.LaunchAttempt attempt = new ScanJob.LaunchAttempt(required(data, key + "attempt"),
                        required(data, key + "controller"),
                        Instant.parse(required(data, key + "time")), Long.parseLong(required(data, key + "version")));
                Receipt receipt = new Receipt(required(data, key + "job"),
                        ScanJobType.valueOf(required(data, key + "type")), required(data, key + "requester"),
                        attempt, data.getProperty(key + "scan"));
                if (receipts.put(receipt.jobId(), receipt) != null) throw new IllegalArgumentException("Duplicate receipt");
            }
        } catch (RuntimeException e) { throw new IOException("Invalid launch journal receipt", e); }
    }

    private static String required(Properties data, String key) {
        String value = data.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing receipt field");
        return value;
    }

    private void persist() throws IOException {
        rejectSymlinks(directory);
        rejectUnsafeFile(stateFile);
        Properties data = new Properties();
        data.setProperty("format", "1");
        data.setProperty("count", Integer.toString(receipts.size()));
        int index = 0;
        for (Receipt receipt : receipts.values()) {
            String key = index++ + ".";
            data.setProperty(key + "job", receipt.jobId());
            data.setProperty(key + "type", receipt.type().name());
            data.setProperty(key + "requester", receipt.requesterId());
            data.setProperty(key + "attempt", receipt.attempt().id());
            data.setProperty(key + "controller", receipt.attempt().controllerId());
            data.setProperty(key + "time", receipt.attempt().dispatchedAt().toString());
            data.setProperty(key + "version", Long.toString(receipt.attempt().resolutionVersion()));
            if (receipt.scanId() != null) data.setProperty(key + "scan", receipt.scanId());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        data.setProperty("checksum", checksum(data));
        data.store(output, "Outstanding native scan receipts; no target or credential contents");
        Path temporary = Files.createTempFile(directory, ".launch-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void rejectSymlinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Launch state paths must not contain symlinks");
        }
    }

    private static Path canonicalCandidate(Path path) throws IOException {
        Path existing = path;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        return existing.toRealPath().resolve(existing.relativize(path)).normalize();
    }

    private static String checksum(Properties data) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String key : data.stringPropertyNames().stream().filter(k -> !k.equals("checksum")).sorted().toList()) {
                String value = data.getProperty(key);
                digest.update((key.length() + ":" + key + value.length() + ":" + value + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("Cannot verify launch journal", e); }
    }

    private static void rejectUnsafeFile(Path path) throws IOException {
        rejectSymlinks(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1)) {
            throw new IOException("Launch state must be an unlinked regular private file");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        failed = true;
        lock.close();
        lockChannel.close();
    }
}
