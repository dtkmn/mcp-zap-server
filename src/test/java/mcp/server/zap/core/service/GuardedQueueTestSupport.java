package mcp.server.zap.core.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.configuration.ScanLaunchSafetyProperties;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.service.jobstore.ScanJobStore;
import mcp.server.zap.core.service.queue.ScanJobLaunchSafety;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

/** Real launch ownership for manually composed queue tests; no untracked execution mode. */
public final class GuardedQueueTestSupport implements AfterEachCallback {
    private final List<ScanJobQueueService> queues = new ArrayList<>();
    private final List<ScanJobLaunchSafety> safeties = new ArrayList<>();
    private final List<Path> roots = new ArrayList<>();

    public ScanJobQueueService guarded(ScanJobQueueService queue) {
        ScanJobStore store = (ScanJobStore) ReflectionTestUtils.getField(queue, "scanJobStore");
        queue.configureLaunchSafety(safetyForStore(store));
        queues.add(queue);
        return queue;
    }

    public ScanJobLaunchSafety safetyForStore(ScanJobStore store) {
        try {
            Path root = Files.createTempDirectory("scan-queue-test-").toRealPath();
            roots.add(root);
            ScanLaunchSafetyProperties properties = new ScanLaunchSafetyProperties();
            properties.setJournalDirectory(root.resolve("launches").toString());
            ScanJobLaunchSafety safety = new ScanJobLaunchSafety(store, properties,
                    root.resolve("reports").toString(), root.resolve("automation").toString());
            safeties.add(safety);
            return safety;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Cancellation fixtures start with current-controller receipts, rather than resurrecting legacy work. */
    public ScanJobQueueService guardedWithCurrentOwnedFixtures(ScanJobQueueService queue) {
        ScanJobStore store = (ScanJobStore) ReflectionTestUtils.getField(queue, "scanJobStore");
        var fixtures = List.copyOf(store.list());
        store.updateAndGet(ignored -> List.of());
        guarded(queue);
        ScanJobLaunchSafety safety = (ScanJobLaunchSafety) ReflectionTestUtils.getField(queue, "launchSafety");
        String worker = (String) ReflectionTestUtils.getField(queue, "workerNodeId");
        for (var original : fixtures) {
            if (original.getStatus() == mcp.server.zap.core.model.ScanJobStatus.RUNNING) {
                var now = java.time.Instant.now();
                var preparing = new mcp.server.zap.core.model.ScanJob(original.getId(), original.getType(),
                        original.getParameters(), original.getCreatedAt(), original.getMaxAttempts(),
                        original.getRequesterId(), original.getIdempotencyKey());
                preparing.claim(worker, now, now.plusSeconds(30));
                store.upsertAll(List.of(preparing));
                var target = new mcp.server.zap.core.service.queue.ScanJobStartTarget(preparing.getId(), preparing.getType(),
                        preparing.getParameters(), mcp.server.zap.core.service.queue.ScanJobClaimToken.from(preparing));
                var attempt = safety.newAttempt(target);
                try {
                    attempt.beforeDispatch();
                    attempt.accepted(original.getZapScanId());
                } finally { attempt.finished(); }
                original.restoreLaunchAttempt(store.load(original.getId()).orElseThrow().getLaunchAttempt());
                original.claim(worker, now, now.plusSeconds(30));
            }
            store.upsertAll(List.of(original));
        }
        // No worker pass here: tests retain explicit control over when cancellation occurs.
        ReflectionTestUtils.invokeMethod(queue, "restoreStateFromStore");
        return queue;
    }

    /** A successful fake native boundary, after preparation has succeeded. */
    public static Answer<String> startReplies(String... scanIds) {
        AtomicInteger index = new AtomicInteger();
        return invocation -> {
            NativeScanLaunch launch = launch(invocation);
            launch.beforeDispatch();
            String scanId = scanIds[Math.min(index.getAndIncrement(), scanIds.length - 1)];
            launch.accepted(scanId);
            return scanId;
        };
    }

    public static NativeScanLaunch launch(InvocationOnMock invocation) {
        return invocation.getArgument(invocation.getArguments().length - 1, NativeScanLaunch.class);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        for (ScanJobQueueService queue : queues) queue.shutdownExecutor();
        for (ScanJobLaunchSafety safety : safeties) {
            try { safety.close(); }
            catch (java.nio.channels.ClosedChannelException alreadyClosedByContext) {
                // ApplicationContextRunner may already have closed its owned bean.
            }
        }
        for (Path root : roots) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
