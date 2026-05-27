package mcp.server.zap.core.service;

import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;

import java.time.Duration;

final class ZapDockerTestSupport {
    private static final Duration ZAP_CONTAINER_STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration ZAP_API_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration ZAP_API_READY_POLL_INTERVAL = Duration.ofMillis(500);

    private ZapDockerTestSupport() {
    }

    static WaitStrategy waitForZapPort() {
        return Wait.forListeningPort().withStartupTimeout(ZAP_CONTAINER_STARTUP_TIMEOUT);
    }

    static void awaitZapApiReady(ClientApi clientApi) throws InterruptedException {
        long deadline = System.nanoTime() + ZAP_API_READY_TIMEOUT.toNanos();
        ClientApiException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                clientApi.core.version();
                return;
            } catch (ClientApiException e) {
                lastFailure = e;
                Thread.sleep(ZAP_API_READY_POLL_INTERVAL.toMillis());
            }
        }
        throw new IllegalStateException(
                "ZAP API did not become ready within " + ZAP_API_READY_TIMEOUT.toSeconds() + " seconds",
                lastFailure);
    }
}
