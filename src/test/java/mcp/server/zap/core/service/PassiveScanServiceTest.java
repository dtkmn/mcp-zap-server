package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

public class PassiveScanServiceTest {
    private EnginePassiveScanAccess passiveScanAccess;
    private PassiveScanService service;

    @BeforeEach
    void setup() {
        passiveScanAccess = mock(EnginePassiveScanAccess.class);
        service = new PassiveScanService(passiveScanAccess);
    }

    @AfterEach
    void tearDown() {
        service.shutdownWaits();
    }

    @Test
    void getPassiveScanStatusReturnsBacklogSummary() throws Exception {
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenReturn(new PassiveScanSnapshot(5, 2, false));

        String result = service.getPassiveScanStatus();

        assertTrue(result.contains("Completed: no"));
        assertTrue(result.contains("Records remaining: 5"));
        assertTrue(result.contains("Active tasks: 2"));
        assertTrue(result.contains("Scan only in scope: false"));
    }

    @Test
    void waitForPassiveScanCompletionReturnsCompletionMessage() throws Exception {
        when(passiveScanAccess.loadPassiveScanSnapshot())
                .thenReturn(new PassiveScanSnapshot(2, 1, true))
                .thenReturn(new PassiveScanSnapshot(0, 0, true));

        String result = service.waitForPassiveScanCompletion(1, 1);

        assertTrue(result.contains("Passive scan backlog drained."));
        assertTrue(result.contains("Completed: yes"));
        assertTrue(result.contains("Records remaining: 0"));
        assertTrue(result.contains("Scan only in scope: true"));
    }

    @Test
    void waitForPassiveScanCompletionTimesOutWhenBacklogPersists() throws Exception {
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenReturn(new PassiveScanSnapshot(3, 1, false));

        String result = service.waitForPassiveScanCompletion(1, 100);

        assertTrue(result.contains("Passive scan wait timed out"));
        assertTrue(result.contains("Completed: no"));
        assertTrue(result.contains("Records remaining: 3"));
    }

    @Test
    void waitForPassiveScanCompletionRejectsNonPositiveTimeout() {
        assertThrowsExactly(IllegalArgumentException.class, () -> service.waitForPassiveScanCompletion(0, 1000));
    }

    @Test
    void waitRejectsTimingValuesAboveServerLimits() {
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.waitForPassiveScanCompletion(Integer.MAX_VALUE, 1000));
        assertThrowsExactly(IllegalArgumentException.class,
                () -> service.waitForPassiveScanCompletion(60, Integer.MAX_VALUE));
    }

    @Test
    void pollDelayCannotExtendDeadlineOrReportLateCompletion() {
        when(passiveScanAccess.loadPassiveScanSnapshot())
                .thenReturn(new PassiveScanSnapshot(3, 1, false))
                .thenReturn(new PassiveScanSnapshot(0, 0, false));

        String result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> service.waitForPassiveScanCompletion(1, 10_000));

        assertTrue(result.contains("timed out"));
        assertTrue(result.contains("Completed: no"));
    }

    @Test
    void engineReadCannotExtendCallerDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenAnswer(invocation -> {
            release.await();
            return new PassiveScanSnapshot(0, 0, false);
        });
        try {
            String result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> service.waitForPassiveScanCompletion(1, 100));
            assertTrue(result.contains("timed out"));
            assertTrue(result.contains("Completed: unknown"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentWaitsAreBoundedAndCancellationReleasesCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new PassiveScanSnapshot(0, 0, false);
        });
        var callers = Executors.newFixedThreadPool(4);
        var waits = new ArrayList<Future<String>>();
        try {
            for (int i = 0; i < 4; i++) {
                waits.add(callers.submit(() -> service.waitForPassiveScanCompletion(60, 1000)));
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrowsExactly(IllegalStateException.class,
                            () -> service.waitForPassiveScanCompletion(60, 1000)));

            // An ordinary status request uses no wait-worker capacity.
            doReturn(new PassiveScanSnapshot(0, 0, false)).when(passiveScanAccess).loadPassiveScanSnapshot();
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertTrue(service.getPassiveScanStatus().contains("Completed: yes")));
            for (Future<String> wait : waits) {
                wait.cancel(true);
            }
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (true) {
                    try {
                        assertTrue(service.waitForPassiveScanCompletion(1, 1).contains("Completed: yes"));
                        break;
                    } catch (IllegalStateException busy) {
                        Thread.sleep(10);
                    }
                }
            });
        } finally {
            release.countDown();
            waits.forEach(wait -> wait.cancel(true));
            callers.shutdownNow();
        }
    }

    @Test
    void callerInterruptionRemainsDistinctFromTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(passiveScanAccess.loadPassiveScanSnapshot()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new PassiveScanSnapshot(0, 0, false);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                service.waitForPassiveScanCompletion(60, 1000);
            } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertInstanceOf(ZapApiException.class, failure.get());
            assertTrue(failure.get().getMessage().contains("interrupted"));
            assertTrue(interrupted.get());
        } finally {
            caller.interrupt();
            release.countDown();
            caller.join(3000);
        }
    }

    @Test
    void statusAndWaitPreserveZapErrors() throws Exception {
        ZapApiException failure = new ZapApiException("boom", new RuntimeException("boom"));
        when(passiveScanAccess.loadPassiveScanSnapshot())
                .thenThrow(failure);

        assertSame(failure, assertThrowsExactly(ZapApiException.class, service::getPassiveScanStatus));
        assertSame(failure, assertThrowsExactly(ZapApiException.class,
                () -> service.waitForPassiveScanCompletion(60, 1000)));
    }
}
