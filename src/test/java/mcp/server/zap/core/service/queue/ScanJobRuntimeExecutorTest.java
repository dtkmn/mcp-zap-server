package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJobType;
import mcp.server.zap.core.service.ActiveScanService;
import mcp.server.zap.core.service.AjaxSpiderService;
import mcp.server.zap.core.service.SpiderScanService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanJobRuntimeExecutorTest {

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
        when(activeScanService.startActiveScanJob("https://example.com", "false", null))
                .thenReturn("active-direct-1");
        when(activeScanService.getActiveScanProgressPercent("active-direct-1")).thenReturn(83);

        String scanId = executor.startScan(ScanJobType.ACTIVE_SCAN, parameters);
        int progress = executor.readProgress(ScanJobType.ACTIVE_SCAN, scanId);
        executor.stopScan(ScanJobType.ACTIVE_SCAN, scanId);

        assertEquals("active-direct-1", scanId);
        assertEquals(83, progress);
        verify(activeScanService).startActiveScanJob("https://example.com", "false", null);
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
        when(activeScanService.startActiveScanAsUserJob("ctx-1", "user-1", "https://example.com", "true", null))
                .thenReturn("active-1");

        String scanId = executor.startScan(ScanJobType.ACTIVE_SCAN_AS_USER, parameters);

        assertEquals("active-1", scanId);
        verify(activeScanService).startActiveScanAsUserJob("ctx-1", "user-1", "https://example.com", "true", null);
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
        when(spiderScanService.startSpiderScanAsUserJob("ctx-1", "user-1", "https://example.com", null, "false", "true"))
                .thenReturn("spider-user-1");
        when(spiderScanService.getSpiderScanProgressPercent("spider-user-1")).thenReturn(64);

        String scanId = executor.startScan(ScanJobType.SPIDER_SCAN_AS_USER, parameters);
        int progress = executor.readProgress(ScanJobType.SPIDER_SCAN_AS_USER, scanId);
        executor.stopScan(ScanJobType.SPIDER_SCAN_AS_USER, scanId);

        assertEquals("spider-user-1", scanId);
        assertEquals(64, progress);
        verify(spiderScanService).startSpiderScanAsUserJob("ctx-1", "user-1", "https://example.com", null, "false", "true");
        verify(spiderScanService).getSpiderScanProgressPercent("spider-user-1");
        verify(spiderScanService).stopSpiderScanJob("spider-user-1");
    }

    @Test
    void routesSpiderScanLifecycleToSpiderService() {
        ActiveScanService activeScanService = mock(ActiveScanService.class);
        SpiderScanService spiderScanService = mock(SpiderScanService.class);
        ScanJobRuntimeExecutor executor = new ScanJobRuntimeExecutor(activeScanService, spiderScanService, null);

        when(spiderScanService.startSpiderScanJob("https://example.com")).thenReturn("spider-1");
        when(spiderScanService.getSpiderScanProgressPercent("spider-1")).thenReturn(42);

        String scanId = executor.startScan(
                ScanJobType.SPIDER_SCAN,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com")
        );
        int progress = executor.readProgress(ScanJobType.SPIDER_SCAN, scanId);
        executor.stopScan(ScanJobType.SPIDER_SCAN, scanId);

        assertEquals("spider-1", scanId);
        assertEquals(42, progress);
        verify(spiderScanService).startSpiderScanJob("https://example.com");
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
                () -> executor.startScan(
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

        when(ajaxSpiderService.startAjaxSpiderJob("https://example.com")).thenReturn("ajax-1");
        when(ajaxSpiderService.getAjaxSpiderProgressPercent()).thenReturn(10);

        String scanId = executor.startScan(
                ScanJobType.AJAX_SPIDER,
                Map.of(ScanJobParameterNames.TARGET_URL, "https://example.com")
        );
        int progress = executor.readProgress(ScanJobType.AJAX_SPIDER, scanId);
        executor.stopScan(ScanJobType.AJAX_SPIDER, scanId);

        assertEquals("ajax-1", scanId);
        assertEquals(10, progress);
        verify(ajaxSpiderService).stopAjaxSpiderJob();
    }
}
