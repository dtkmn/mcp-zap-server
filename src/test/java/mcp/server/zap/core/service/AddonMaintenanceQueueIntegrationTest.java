package mcp.server.zap.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import mcp.server.zap.core.configuration.AddonMaintenanceProperties;
import mcp.server.zap.core.configuration.ScanLimitProperties;
import mcp.server.zap.core.configuration.ZapInitializationProperties;
import mcp.server.zap.core.gateway.EngineMaintenanceGate;
import mcp.server.zap.core.gateway.EngineScanExecution;
import mcp.server.zap.core.gateway.TimeoutZapClientApi;
import mcp.server.zap.core.gateway.ZapEngineRuntimeAccess;
import mcp.server.zap.core.gateway.ZapEngineScanExecution;
import mcp.server.zap.core.model.ScanJobStatus;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/** Exercises the real queued service, initialization aspect, engine adapter and guarded transport. */
class AddonMaintenanceQueueIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void maintenanceDefersQueuedStartsWithoutHttpOrRetryConsumptionAndReleaseAllowsStart(
            boolean networkDefaultsNeedUpdate) throws Exception {
        var properties = new AddonMaintenanceProperties();
        properties.setStateDirectory(directory.toString());
        properties.setUpdatesEnabled(true);
        var gate = new EngineMaintenanceGate(properties);
        var defaults = new ZapInitializationProperties();
        defaults.setUserAgent("maintenance-test-agent");
        var actionRequests = new AtomicInteger();
        var scanStarts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var httpExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(httpExecutor);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.contains("/action/")) actionRequests.incrementAndGet();
                String value = switch (path) {
                    case "/xml/core/view/version/" -> "2.17.0";
                    case "/xml/network/view/getDefaultUserAgent/" -> networkDefaultsNeedUpdate ? "stale-agent" : defaults.getUserAgent();
                    case "/xml/network/view/getConnectionTimeout/" -> String.valueOf(defaults.getConnectionTimeoutInSecs());
                    case "/xml/network/view/getDnsTtlSuccessfulQueries/" -> String.valueOf(defaults.getDnsTtlSuccessfulQueries());
                    case "/xml/spider/action/scan/" -> {
                        scanStarts.incrementAndGet();
                        yield "77";
                    }
                    default -> "OK";
                };
                byte[] body = ("<Result>" + value + "</Result>").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            var client = new TimeoutZapClientApi("127.0.0.1", server.getAddress().getPort(), "test-key", 500, 1000, gate);
            var initialization = new ZapInitializationService(new ZapEngineRuntimeAccess(client), defaults);
            var proxy = new AspectJProxyFactory(new ZapEngineScanExecution(client));
            proxy.setProxyTargetClass(true);
            proxy.addAspect(initialization);
            EngineScanExecution execution = proxy.getProxy();
            var limits = new ScanLimitProperties();
            var validation = mock(UrlValidationService.class);
            var spider = new SpiderScanService(execution, validation, limits);
            var queue = new ScanJobQueueService(mock(ActiveScanService.class), spider, validation, limits,
                    new ScanJobQueueService.RetryPolicy(1, 0, 0, 1),
                    new ScanJobQueueService.RetryPolicy(1, 0, 0, 1), false);
            try {
                String jobId;
                try (var ignored = gate.beginMaintenance()) {
                    String response = queue.queueSpiderScan("http://fixture.invalid", null);
                    jobId = response.lines().filter(line -> line.startsWith("Job ID: "))
                            .map(line -> line.substring("Job ID: ".length()).trim()).findFirst().orElseThrow();
                    queue.processQueueOnceForTesting();
                    var waiting = queue.getJobForTesting(jobId);
                    assertThat(waiting.getStatus()).isEqualTo(ScanJobStatus.QUEUED);
                    assertThat(waiting.getAttempts()).isZero();
                    assertThat(waiting.getBusyWaitCount()).isGreaterThanOrEqualTo(2);
                    assertThat(actionRequests.get()).isZero();
                    assertThat(scanStarts.get()).isZero();
                    assertThat(gate.isQuarantined()).isFalse();
                }
                queue.processQueueOnceForTesting();
                var running = queue.getJobForTesting(jobId);
                assertThat(running.getStatus()).isEqualTo(ScanJobStatus.RUNNING);
                assertThat(running.getAttempts()).isEqualTo(1);
                assertThat(running.getZapScanId()).isEqualTo("77");
                assertThat(scanStarts.get()).isEqualTo(1);
                assertThat(actionRequests.get()).isPositive();
                assertThat(gate.isQuarantined()).isFalse();
            } finally { queue.shutdownExecutor(); }
        } finally { server.stop(0); }
    }
}
