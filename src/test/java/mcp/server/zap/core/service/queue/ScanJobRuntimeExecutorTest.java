package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.gateway.NativeScanLaunch;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.ClientSpiderService;
import mcp.server.zap.core.service.SpiderScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScanJobRuntimeExecutorTest {
    private final NativeScanLaunch launch = mock(NativeScanLaunch.class);
    private final ScanJobClaimToken claim = new ScanJobClaimToken("worker", "fence");

    private String start(ScanJobRuntimeExecutor executor, ScanJobType type, Map<String, String> parameters) {
        return executor.startScan(new ScanJobStartTarget("queued-job", type, parameters, claim), launch);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "not-a-number", "1.5", "2147483648"})
    void rejectsMalformedStoredClientSpiderDepthBeforeStartingCrawl(String storedDepth) {
        ClientSpiderService clientSpiderService = mock(ClientSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, null, clientSpiderService);
        Map<String, String> parameters = new HashMap<>();
        parameters.put(ScanJobParameterNames.TARGET_URL, "https://example.com");
        parameters.put(ScanJobParameterNames.MAX_DEPTH, storedDepth);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> start(executor, ScanJobType.CLIENT_SPIDER, parameters));

        assertEquals("Stored Client Spider maxDepth must be a valid integer", exception.getMessage());
        assertInstanceOf(NumberFormatException.class, exception.getCause());
        verifyNoInteractions(clientSpiderService);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, Integer.MAX_VALUE})
    void preservesValidStoredClientSpiderDepth(int storedDepth) {
        ClientSpiderService clientSpiderService = mock(ClientSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, null, clientSpiderService);
        when(clientSpiderService.startClientSpiderJob("https://example.com", storedDepth, null, null, launch)).thenReturn("client-depth");

        String scanId = start(executor, ScanJobType.CLIENT_SPIDER, Map.of(
                ScanJobParameterNames.TARGET_URL, "https://example.com",
                ScanJobParameterNames.MAX_DEPTH, Integer.toString(storedDepth)));

        assertEquals("client-depth", scanId);
        verify(clientSpiderService).startClientSpiderJob("https://example.com", storedDepth, null, null, launch);
    }

    @Test
    void routesClientSpiderLifecycleUsingItsNativeScanId() {
        ClientSpiderService clientSpiderService = mock(ClientSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, null, clientSpiderService);
        when(clientSpiderService.startClientSpiderJob("https://example.com", 4, null, null, launch)).thenReturn("client-17");
        when(clientSpiderService.getClientSpiderProgressPercent("client-17")).thenReturn(62);

        String scanId = start(executor, ScanJobType.CLIENT_SPIDER, Map.of(
                ScanJobParameterNames.TARGET_URL, "https://example.com", ScanJobParameterNames.MAX_DEPTH, "4"));
        assertEquals("client-17", scanId);
        assertEquals(62, executor.readProgress(ScanJobType.CLIENT_SPIDER, scanId));
        executor.stopScan(ScanJobType.CLIENT_SPIDER, scanId);

        verify(clientSpiderService).stopClientSpiderJob("client-17");
        start(executor, ScanJobType.CLIENT_SPIDER, Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"));
        verify(clientSpiderService).startClientSpiderJob("https://example.com", null, null, null, launch);
    }

    @Test
    void routesAuthenticatedClientSpiderUsingStoredContextAndUserNames() {
        ClientSpiderService clientSpiderService = mock(ClientSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, null, clientSpiderService);
        when(clientSpiderService.startClientSpiderJob("https://example.com", null, "Application", "alice", launch))
                .thenReturn("client-user-17");

        String scanId = start(executor, ScanJobType.CLIENT_SPIDER, Map.of(
                ScanJobParameterNames.TARGET_URL, "https://example.com",
                ScanJobParameterNames.CONTEXT_NAME, "Application",
                ScanJobParameterNames.USER_NAME, "alice"));

        assertEquals("client-user-17", scanId);
        verify(clientSpiderService).startClientSpiderJob("https://example.com", null, "Application", "alice", launch);
    }

    @Test
    void routesActiveScanLifecycleAndNormalizesBlankPolicy() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        Map<String, String> parameters = Map.of(
                ScanJobParameterNames.TARGET_URL, "https://example.com",
                ScanJobParameterNames.RECURSE, "false",
                ScanJobParameterNames.POLICY, "   "
        );
        when(activeScanService.startActiveScanJob("https://example.com", "false", null, launch))
                .thenReturn("active-direct-1");
        when(activeScanService.getActiveScanProgressPercent("active-direct-1")).thenReturn(83);

        String scanId = start(executor, ScanJobType.ACTIVE_SCAN, parameters);
        int progress = executor.readProgress(ScanJobType.ACTIVE_SCAN, scanId);
        executor.stopScan(ScanJobType.ACTIVE_SCAN, scanId);

        assertEquals("active-direct-1", scanId);
        assertEquals(83, progress);
        verify(activeScanService).startActiveScanJob("https://example.com", "false", null, launch);
        verify(activeScanService).getActiveScanProgressPercent("active-direct-1");
        verify(activeScanService).stopActiveScanJob("active-direct-1");
    }

    @Test
    void startsActiveScanAsUserWithNormalizedPolicy() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        Map<String, String> parameters = Map.of(
                ScanJobParameterNames.CONTEXT_ID, "ctx-1",
                ScanJobParameterNames.USER_ID, "user-1",
                ScanJobParameterNames.TARGET_URL, "https://example.com",
                ScanJobParameterNames.RECURSE, "true",
                ScanJobParameterNames.POLICY, "   "
        );
        when(activeScanService.startActiveScanAsUserJob("ctx-1", "user-1", "https://example.com", "true", null, launch))
                .thenReturn("active-1");

        String scanId = start(executor, ScanJobType.ACTIVE_SCAN_AS_USER, parameters);

        assertEquals("active-1", scanId);
        verify(activeScanService).startActiveScanAsUserJob("ctx-1", "user-1", "https://example.com", "true", null, launch);
    }

    @Test
    void routesSpiderScanAsUserLifecycleAndNormalizesBlankMaxChildren() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        Map<String, String> parameters = Map.of(
                ScanJobParameterNames.CONTEXT_ID, "ctx-1",
                ScanJobParameterNames.USER_ID, "user-1",
                ScanJobParameterNames.TARGET_URL, "https://example.com",
                ScanJobParameterNames.MAX_CHILDREN, "   ",
                ScanJobParameterNames.RECURSE, "false",
                ScanJobParameterNames.SUBTREE_ONLY, "true"
        );
        when(spiderScanService.startSpiderScanAsUserJob("ctx-1", "user-1", "https://example.com", null, "false", "true", launch))
                .thenReturn("spider-user-1");
        when(spiderScanService.getSpiderScanProgressPercent("spider-user-1")).thenReturn(64);

        String scanId = start(executor, ScanJobType.SPIDER_SCAN_AS_USER, parameters);
        int progress = executor.readProgress(ScanJobType.SPIDER_SCAN_AS_USER, scanId);
        executor.stopScan(ScanJobType.SPIDER_SCAN_AS_USER, scanId);

        assertEquals("spider-user-1", scanId);
        assertEquals(64, progress);
        verify(spiderScanService).startSpiderScanAsUserJob("ctx-1", "user-1", "https://example.com", null, "false", "true", launch);
        verify(spiderScanService).getSpiderScanProgressPercent("spider-user-1");
        verify(spiderScanService).stopSpiderScanJob("spider-user-1");
    }

    @Test
    void routesSpiderScanLifecycleToSpiderService() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        when(spiderScanService.startSpiderScanJob("https://example.com", launch)).thenReturn("spider-1");
        when(spiderScanService.getSpiderScanProgressPercent("spider-1")).thenReturn(42);

        String scanId = start(executor,
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com")
        );
        int progress = executor.readProgress(ScanJobType.SPIDER_SCAN, scanId);
        executor.stopScan(ScanJobType.SPIDER_SCAN, scanId);

        assertEquals("spider-1", scanId);
        assertEquals(42, progress);
        verify(spiderScanService).startSpiderScanJob("https://example.com", launch);
        verify(spiderScanService).getSpiderScanProgressPercent("spider-1");
        verify(spiderScanService).stopSpiderScanJob("spider-1");
    }

    @Test
    void rejectsAjaxSpiderWhenRuntimeDoesNotProvideAjaxService() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> start(executor,
                        ScanJobType.AJAX_SPIDER,
                        Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com")
                )
        );

        assertEquals("AJAX Spider service is not available in this runtime", exception.getMessage());
    }

    @Test
    void routesAjaxSpiderLifecycleWhenServiceIsAvailable() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        AjaxSpiderService ajaxSpiderService = mock(AjaxSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(
                activeScanService,
                spiderScanService,
                ajaxSpiderService
        );

        when(ajaxSpiderService.startAjaxSpiderJobTracked("https://example.com", "queued-job", claim, launch)).thenReturn("ajax-1");
        when(ajaxSpiderService.isAjaxSpiderRunning()).thenReturn(true, false);

        String scanId = start(executor,
                ScanJobType.AJAX_SPIDER,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com")
        );
        int runningSignal = executor.readProgress(ScanJobType.AJAX_SPIDER, scanId);
        int stoppedSignal = executor.readProgress(ScanJobType.AJAX_SPIDER, scanId);
        executor.stopScan(ScanJobType.AJAX_SPIDER, scanId);

        assertEquals("ajax-1", scanId);
        assertEquals(0, runningSignal);
        assertEquals(100, stoppedSignal);
        verify(ajaxSpiderService).stopAjaxSpiderJob();
    }

    @Test
    void queuedAjaxStartCarriesDurableOwnershipToService() {
        AjaxSpiderService ajaxSpiderService = mock(AjaxSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, ajaxSpiderService);
        ScanJobClaimToken claim = new ScanJobClaimToken("worker", "fence");
        ScanJobStartTarget target = new ScanJobStartTarget("ajax-job", ScanJobType.AJAX_SPIDER,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"), claim);
        when(ajaxSpiderService.startAjaxSpiderJobTracked("https://example.com", "ajax-job", claim, launch)).thenReturn("ajax-1");

        assertEquals("ajax-1", executor.startScan(target, launch));

        verify(ajaxSpiderService).startAjaxSpiderJobTracked("https://example.com", "ajax-job", claim, launch);
    }

    @Test
    void queuedAjaxAcceptanceUsesTheOriginalLaunchCallbacks() {
        AjaxSpiderService ajaxSpiderService = mock(AjaxSpiderService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(null, null, ajaxSpiderService);
        ScanJobStartTarget target = new ScanJobStartTarget("ajax-job", ScanJobType.AJAX_SPIDER,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"), claim);
        when(ajaxSpiderService.startAjaxSpiderJobTracked("https://example.com", "ajax-job", claim, launch))
                .thenAnswer(invocation -> {
                    NativeScanLaunch forwarded = invocation.getArgument(3);
                    forwarded.beforeDispatch();
                    forwarded.accepted("ajax-1");
                    return "ajax-1";
                });

        assertEquals("ajax-1", executor.startScan(target, launch));

        verify(launch).beforeDispatch();
        verify(launch).accepted("ajax-1");
    }

    @Test
    void missingLaunchOwnershipIsRejectedBeforeCallingAnyScanService() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, null, null);
        ScanJobStartTarget target = new ScanJobStartTarget("queued-job", ScanJobType.ACTIVE_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com"), claim);

        assertThrows(NullPointerException.class, () -> executor.startScan(target, null));

        verifyNoInteractions(activeScanService);
    }
}
