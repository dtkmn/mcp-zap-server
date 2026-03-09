package mcp.server.zap.core.service.revocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void revokeIfActiveShouldAllowReuseAfterStoredExpiry() {
        String tokenId = "refresh-token-2";
        Instant alreadyExpired = Instant.now().minusSeconds(60);
        Instant activeExpiry = Instant.now().plusSeconds(3600);

        boolean first = store.revokeIfActive(tokenId, alreadyExpired);
        boolean second = store.revokeIfActive(tokenId, activeExpiry);

        assertThat(first).isTrue();
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
