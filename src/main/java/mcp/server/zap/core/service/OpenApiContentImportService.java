package mcp.server.zap.core.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import mcp.server.zap.core.configuration.OpenApiContentImportProperties;
import mcp.server.zap.core.gateway.EngineApiImportAccess;
import mcp.server.zap.core.service.protection.ClientWorkspaceResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.zaproxy.clientapi.core.ClientApiException;

/** Stages definitions for synchronous ZAP import; uncertain calls retain bounded recovery files. */
@Slf4j
@Service
public class OpenApiContentImportService {
    private static final String DEFINITION_FILE = "definition.json";
    private static final String RUN_PATTERN = "import-[0-9a-f]{64}-[0-9a-f-]{36}";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwxr-x---");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-r-----");
    private final EngineApiImportAccess engine;
    private final OpenApiContentPolicy policy;
    private final OpenApiContentImportProperties properties;
    private final ClientWorkspaceResolver workspaceResolver;
    private final Clock clock;
    private final Semaphore inFlight = new Semaphore(4);
    // All filesystem mutations and lease changes are serialized, but engine calls are not.
    private final Set<Path> activeDirectories = new HashSet<>();
    private Path localRoot;
    private Path zapRoot;
    private Object rootFileKey;
    private FileChannel lockChannel;
    private FileLock writerLock;

    @Value("${zap.report.directory:/zap/wrk}")
    private String reportDirectory = "/zap/wrk";
    @Value("${zap.automation.local-directory:/zap/wrk/automation}")
    private String automationLocalDirectory = "/zap/wrk/automation";
    @Value("${zap.automation.zap-directory:/zap/wrk/automation}")
    private String automationZapDirectory = "/zap/wrk/automation";

    @Autowired
    public OpenApiContentImportService(EngineApiImportAccess engine, UrlValidationService urlValidationService,
                                       OpenApiContentImportProperties properties, ClientWorkspaceResolver workspaceResolver) {
        this(engine, urlValidationService, properties, workspaceResolver, Clock.systemUTC());
    }

    OpenApiContentImportService(EngineApiImportAccess engine, UrlValidationService urlValidationService,
                                OpenApiContentImportProperties properties, ClientWorkspaceResolver workspaceResolver,
                                Clock clock) {
        this.engine = engine;
        this.policy = new OpenApiContentPolicy(urlValidationService);
        this.properties = properties;
        this.workspaceResolver = workspaceResolver;
        this.clock = clock;
    }

    @PostConstruct
    synchronized void initialize() {
        if (!properties.isEnabled() || writerLock != null) {
            return;
        }
        if (properties.getMaxRetainedImports() < 1 || properties.getMaxRetainedImports() > 128
                || properties.getRetentionMinutes() < 1 || properties.getRetentionMinutes() > 1440) {
            throw new IllegalStateException("OpenAPI content retention must be 1–128 imports and 1–1440 minutes");
        }
        try {
            localRoot = absoluteRoot(properties.getLocalDirectory());
            zapRoot = absoluteRoot(properties.getZapDirectory());
            requireSeparateRoot(localRoot, reportDirectory);
            requireSeparateRoot(localRoot, automationLocalDirectory);
            requireSeparateRoot(zapRoot, reportDirectory);
            requireSeparateRoot(zapRoot, automationZapDirectory);
            checkRoot();
            rootFileKey = Files.readAttributes(localRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            Path lockPath = localRoot.resolve(".content-import-writer.lock");
            lockChannel = FileChannel.open(lockPath,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            PosixFileAttributes lockAttributes = Files.readAttributes(lockPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!lockAttributes.isRegularFile() || !lockAttributes.owner().equals(rootAttributes().owner())
                    || lockAttributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                    || lockAttributes.permissions().stream().anyMatch(p -> p.name().startsWith("OTHERS_"))) {
                throw new IOException("Unsafe writer lock");
            }
            writerLock = lockChannel.tryLock();
            if (writerLock == null) {
                throw new IOException("Staging root has another writer");
            }
            reapExpiredFiles();
        } catch (IOException | RuntimeException e) {
            close();
            throw new IllegalStateException("OpenAPI content import requires a dedicated, pre-provisioned POSIX shared directory with one MCP writer, owner access, group read access, and no symlinks or group/other write access");
        }
    }

    public String importContent(String source, String hostOverride) {
        if (!properties.isEnabled()) {
            throw new IllegalArgumentException("OpenAPI content import is disabled; configure and enable its dedicated shared filesystem first");
        }
        // Capture trusted identity at invocation; admission also bounds parser work.
        String workspace = workspaceResolver.resolveCurrentWorkspaceId();
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalStateException("OpenAPI content import requires a resolved workspace");
        }
        if (!inFlight.tryAcquire()) {
            throw new IllegalStateException("OpenAPI content import capacity is busy; retry after current imports finish");
        }
        Path directory = null;
        boolean completed = false;
        boolean dispatched = false;
        try {
            byte[] definition = policy.prepare(source, hostOverride);
            directory = stage(definition, workspace);
            String zapPath = zapRoot.resolve(directory.getFileName()).resolve(DEFINITION_FILE).toString();
            checkReadyForDispatch(directory);
            EngineApiImportAccess.ImportResult result;
            try {
                dispatched = true;
                result = engine.importOpenApiFile(new EngineApiImportAccess.FileImportRequest(zapPath, hostOverride.trim()));
                if (result == null) {
                    throw new IllegalStateException("Missing import response");
                }
            } catch (RuntimeException e) {
                String code = engineErrorCode(e);
                if ("does_not_exist".equals(code)) {
                    completed = true;
                    throw new IllegalArgumentException("ZAP could not read the staged definition; check the shared mount mapping and reader permissions. Import did not start");
                }
                if ("illegal_parameter".equals(code)) {
                    completed = true;
                    throw new IllegalArgumentException("ZAP rejected an import parameter; check the target URL and shared mount configuration. Import did not start");
                }
                if ("bad_external_data".equals(code)) {
                    completed = true;
                    throw new IllegalArgumentException("ZAP rejected the staged OpenAPI definition. Review the supported definition format and restricted content contract");
                }
                log.warn("OpenAPI content import outcome is unconfirmed; bounded staging retained for recovery");
                throw new IllegalStateException("OpenAPI content import outcome is unconfirmed. ZAP may still be processing it; inspect the ZAP session before retrying. The staged definition is retained until configured cleanup");
            }
            // ZAP's OpenAPI import is synchronous. A returned warning list can mean partial import.
            completed = true;
            int messages = result.values().size();
            return messages == 0
                    ? "OpenAPI content import completed with no reported import warnings. Review imported endpoints before starting an active scan."
                    : "OpenAPI content import returned " + messages + " ZAP warning/error message(s); import may be partial. Message contents are withheld because they can contain definition data or internal paths. Review the imported endpoints and ZAP diagnostics before proceeding.";
        } finally {
            if (directory != null) {
                finish(directory, completed || !dispatched);
            }
            inFlight.release();
        }
    }

    private synchronized Path stage(byte[] definition, String workspace) {
        initialize();
        Path directory = null;
        try {
            checkRoot();
            reapExpiredFiles();
            if (retainedCount() >= properties.getMaxRetainedImports()) {
                throw new IllegalStateException("OpenAPI content staging capacity is full; inspect uncertain imports or wait for configured cleanup");
            }
            directory = localRoot.resolve("import-" + workspaceHash(workspace) + "-" + UUID.randomUUID());
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
            Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setGroup(rootAttributes().group());
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
            Path file = directory.resolve(DEFINITION_FILE);
            try (FileChannel output = FileChannel.open(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))) {
                Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setGroup(rootAttributes().group());
                ByteBuffer bytes = ByteBuffer.wrap(definition);
                while (bytes.hasRemaining()) {
                    output.write(bytes);
                }
                output.force(true);
            }
            // Atomic creation honors umask; restore the explicitly configured reader group access.
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
            // Persist a non-secret recovery timestamp, including across process restarts.
            Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.from(clock.instant()));
            activeDirectories.add(directory);
            return directory;
        } catch (IOException e) {
            if (directory != null) {
                try {
                    Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
                } catch (IOException cleanupFailure) {
                    log.warn("OpenAPI content staging permissions could not be restored for local cleanup");
                }
                deleteRun(directory);
            }
            throw new IllegalStateException("Unable to stage OpenAPI content in its protected shared filesystem");
        }
    }

    private synchronized void finish(Path directory, boolean completed) {
        if (completed) {
            deleteRun(directory);
        } else {
            try {
                checkRoot();
                if (managedDirectory(directory)) {
                    Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.from(clock.instant()));
                }
            } catch (IOException e) {
                // Keep the lease if recovery time cannot be persisted; capacity still bounds storage.
                log.warn("OpenAPI content recovery timestamp could not be persisted; automatic cleanup paused for this import");
                return;
            }
        }
        activeDirectories.remove(directory);
    }

    private synchronized void checkReadyForDispatch(Path directory) {
        try {
            checkRoot();
            Path file = directory.resolve(DEFINITION_FILE);
            var attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!managedDirectory(directory) || !attributes.isRegularFile()
                    || !attributes.owner().equals(rootAttributes().owner())
                    || !attributes.group().equals(rootAttributes().group())
                    || !attributes.permissions().equals(FILE_PERMISSIONS)) {
                throw new IOException("Unsafe staged definition");
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("OpenAPI content staging changed before dispatch; import was not sent to ZAP");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    synchronized void reapExpiredFiles() {
        if (!properties.isEnabled() || writerLock == null) {
            return;
        }
        try {
            checkRoot();
            var cutoff = clock.instant().minus(Duration.ofMinutes(properties.getRetentionMinutes()));
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(localRoot, "import-*")) {
                for (Path directory : entries) {
                    if (managedDirectory(directory) && !activeDirectories.contains(directory)
                            && Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                        deleteRun(directory);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("OpenAPI content retention cleanup could not inspect the protected staging directory");
        }
    }

    private int retainedCount() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(localRoot, "import-*")) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                iterator.next();
                count++;
                if (count >= properties.getMaxRetainedImports()) {
                    return count;
                }
            }
        }
        return count;
    }

    private boolean managedDirectory(Path directory) throws IOException {
        if (!directory.getFileName().toString().matches(RUN_PATTERN)
                || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        PosixFileAttributes attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.owner().equals(rootAttributes().owner())
                && attributes.permissions().equals(DIRECTORY_PERMISSIONS);
    }

    private void deleteRun(Path directory) {
        try {
            checkRoot();
            if (!managedDirectory(directory)) {
                return;
            }
            Path definition = directory.resolve(DEFINITION_FILE);
            if (Files.exists(definition, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(definition) || !Files.isRegularFile(definition, LinkOption.NOFOLLOW_LINKS))) {
                return;
            }
            Files.deleteIfExists(definition);
            Files.delete(directory);
        } catch (IOException e) {
            // Leave a bounded leftover for the reaper instead of misreporting an import outcome.
            log.warn("OpenAPI content staging cleanup deferred");
        }
    }

    private void checkRoot() throws IOException {
        long writerUid = ((Number) Files.getAttribute(localRoot, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        for (Path ancestor = localRoot; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) {
                throw new IOException("Symlink staging ancestor");
            }
            if (!ancestor.equals(localRoot)) {
                var attributes = Files.readAttributes(ancestor, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                long ownerUid = ((Number) Files.getAttribute(ancestor, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
                if (ownerUid != 0 && ownerUid != writerUid) {
                    throw new IOException("Untrusted staging ancestor owner");
                }
                boolean writableByOthers = attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                        || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE);
                // A standard sticky temporary directory protects entries owned by this writer.
                boolean sticky = (((Number) Files.getAttribute(ancestor, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 01000) != 0;
                if (writableByOthers && !sticky) {
                    throw new IOException("Untrusted writable staging ancestor");
                }
            }
        }
        PosixFileAttributes attributes = rootAttributes();
        var permissions = attributes.permissions();
        if (!attributes.isDirectory() || !permissions.containsAll(DIRECTORY_PERMISSIONS)
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.stream().anyMatch(p -> p.name().startsWith("OTHERS_"))
                || !attributes.owner().equals(localRoot.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name")))) {
            throw new IOException("Unsafe staging root");
        }
        if (rootFileKey != null && !Objects.equals(rootFileKey,
                Files.readAttributes(localRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) {
            throw new IOException("Staging root replaced");
        }
    }

    private PosixFileAttributes rootAttributes() throws IOException {
        return Files.readAttributes(localRoot, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path absoluteRoot(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("Shared staging roots are required");
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute() || path.getNameCount() == 0 || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("Shared staging roots must be absolute, non-root paths without traversal");
        }
        return path;
    }

    private static void requireSeparateRoot(Path staging, String readableRoot) {
        Path readable = Path.of(readableRoot).toAbsolutePath().normalize();
        if (staging.startsWith(readable) || readable.startsWith(staging)) {
            throw new IllegalArgumentException("Content staging must be separate from report and automation roots");
        }
    }

    private static String workspaceHash(String workspace) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(workspace.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String engineErrorCode(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof ClientApiException apiFailure && apiFailure.getCode() != null) {
                return apiFailure.getCode().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    @PreDestroy
    synchronized void close() {
        try {
            if (writerLock != null) {
                writerLock.release();
            }
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (IOException e) {
            log.warn("OpenAPI content writer lock could not be released");
        } finally {
            writerLock = null;
            lockChannel = null;
        }
    }
}
