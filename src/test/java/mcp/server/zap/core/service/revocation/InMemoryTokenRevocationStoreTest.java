package mcp.server.zap.core.service.revocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenRevocationStoreTest {

    private InMemoryTokenRevocationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTokenRevocationStore();
    }

    @Test
    void revokeIfActiveShouldSucceedOnlyOnceForActiveToken() {
        String tokenId = "refresh-token-1";
        Instant expiresAt = Instant.now().plusSeconds(3600);

        boolean first = store.revokeIfActive(tokenId, expiresAt);
        boolean second = store.revokeIfActive(tokenId, expiresAt);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void revokeIfActiveShouldRejectExpiredInputWithoutBlockingLaterActiveInput() {
        String tokenId = "refresh-token-2";
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        store = new InMemoryTokenRevocationStore(Clock.fixed(now, ZoneOffset.UTC));
        Instant alreadyExpired = now.minusSeconds(61);
        Instant activeExpiry = now.plusSeconds(3600);

        boolean first = store.revokeIfActive(tokenId, alreadyExpired);
        assertThat(first).isFalse();
        assertThat(store.size()).isZero();
        boolean second = store.revokeIfActive(tokenId, activeExpiry);

        assertThat(second).isTrue();
        assertThat(store.isRevoked(tokenId)).isTrue();
    }

    @Test
    void revokeIfActiveShouldBeAtomicUnderConcurrency() throws InterruptedException {
        String tokenId = "refresh-token-3";
        Instant expiresAt = Instant.now().plusSeconds(3600);
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successfulClaims = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (store.revokeIfActive(tokenId, expiresAt)) {
                    successfulClaims.incrementAndGet();
                }
                done.countDown();
            });
            worker.start();
        }

        ready.await();
        start.countDown();
        done.await();

        assertThat(successfulClaims.get()).isEqualTo(1);
    }
}
