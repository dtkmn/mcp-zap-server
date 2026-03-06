package mcp.server.zap.service;

import mcp.server.zap.configuration.ScanLimitProperties;
import mcp.server.zap.exception.ZapApiException;
import mcp.server.zap.model.ScanJob;
import mcp.server.zap.model.ScanJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ScanJobQueueServiceTest {

    private ActiveScanService activeScanService;
    private SpiderScanService spiderScanService;
    private UrlValidationService urlValidationService;
    private ScanLimitProperties scanLimitProperties;
    private ScanJobQueueService service;

    @BeforeEach
    void setup() {
        activeScanService = mock(ActiveScanService.class);
        spiderScanService = mock(SpiderScanService.class);
        urlValidationService = mock(UrlValidationService.class);
        scanLimitProperties = mock(ScanLimitProperties.class);

        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(1);
        when(scanLimitProperties.getMaxConcurrentSpiderScans()).thenReturn(1);

        service = new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                3,
                false
        );
    }

    private ScanJobQueueService newServiceWithPolicies(
            ScanJobQueueService.RetryPolicy activePolicy,
            ScanJobQueueService.RetryPolicy spiderPolicy
    ) {
        return new ScanJobQueueService(
                activeScanService,
                spiderScanService,
                urlValidationService,
                scanLimitProperties,
                activePolicy,
                spiderPolicy,
                false
        );
    }

    @Test
    void queueActiveScanStartsImmediatelyWhenCapacityAvailable() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-101");

        String response = service.queueActiveScan("http://example.com", null, null);
        String jobId = extractJobId(response);

        ScanJob job = service.getJobForTesting(jobId);
        assertNotNull(job);
        assertEquals(ScanJobStatus.RUNNING, job.getStatus());
        assertEquals("A-101", job.getZapScanId());
        assertEquals(1, job.getAttempts());
        verify(urlValidationService).validateUrl("http://example.com");
        verify(activeScanService).startActiveScanJob("http://example.com", "true", null);
    }

    @Test
    void enforcesConcurrencyAndStartsQueuedJobWhenSlotFrees() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenReturn("A-1", "A-2");
        when(activeScanService.getActiveScanProgressPercent("A-1"))
                .thenReturn(0, 100);

        String firstResponse = service.queueActiveScan("http://example.com/1", "true", null);
        String secondResponse = service.queueActiveScan("http://example.com/2", "true", null);

        String firstJobId = extractJobId(firstResponse);
        String secondJobId = extractJobId(secondResponse);

        ScanJob firstJob = service.getJobForTesting(firstJobId);
        ScanJob secondJob = service.getJobForTesting(secondJobId);

        assertEquals(ScanJobStatus.RUNNING, firstJob.getStatus());
        assertEquals(ScanJobStatus.QUEUED, secondJob.getStatus());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());

        service.processQueueOnceForTesting();

        assertEquals(ScanJobStatus.SUCCEEDED, firstJob.getStatus());
        assertEquals(ScanJobStatus.QUEUED, secondJob.getStatus());

        service.processQueueOnceForTesting();

        assertEquals(ScanJobStatus.RUNNING, secondJob.getStatus());
        assertEquals("A-2", secondJob.getZapScanId());
        verify(activeScanService, times(2)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void cancelQueuedJobMarksCancelled() {
        when(scanLimitProperties.getMaxConcurrentActiveScans()).thenReturn(0);

        String response = service.queueActiveScan("http://example.com", "true", null);
        String jobId = extractJobId(response);

        String cancelMessage = service.cancelScanJob(jobId);
        ScanJob job = service.getJobForTesting(jobId);

        assertTrue(cancelMessage.contains("cancelled"));
        assertEquals(ScanJobStatus.CANCELLED, job.getStatus());
        verify(activeScanService, never()).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void retryCancelledJobStartsAgainWhenRetryBudgetAvailable() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenReturn("A-1")
                .thenReturn("A-retry");

        String response = service.queueActiveScan("http://example.com", "true", null);
        String jobId = extractJobId(response);

        service.cancelScanJob(jobId);
        ScanJob cancelledJob = service.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.CANCELLED, cancelledJob.getStatus());
        assertEquals(1, cancelledJob.getAttempts());

        String retryMessage = service.retryScanJob(jobId);

        ScanJob retriedJob = service.getJobForTesting(jobId);
        assertTrue(retryMessage.contains("Retry queued"));
        assertEquals(ScanJobStatus.RUNNING, retriedJob.getStatus());
        assertEquals(2, retriedJob.getAttempts());
        assertEquals("A-retry", retriedJob.getZapScanId());
        verify(activeScanService).stopActiveScanJob("A-1");
        verify(activeScanService, times(2)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void cancelRunningJobStopsZapScan() {
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-stop");

        String response = service.queueActiveScan("http://example.com", "true", null);
        String jobId = extractJobId(response);

        String cancelMessage = service.cancelScanJob(jobId);
        ScanJob job = service.getJobForTesting(jobId);

        assertTrue(cancelMessage.contains("RUNNING"));
        assertEquals(ScanJobStatus.CANCELLED, job.getStatus());
        verify(activeScanService).stopActiveScanJob("A-stop");
    }

    @Test
    void startupBackoffDefersRetryUntilDueTime() {
        ScanJobQueueService delayedService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(3, 60_000, 60_000, 2.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("boom", new RuntimeException("boom")));

        String response = delayedService.queueActiveScan("http://example.com", "true", null);
        String jobId = extractJobId(response);

        ScanJob job = delayedService.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.QUEUED, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertNotNull(job.getNextAttemptAt());

        delayedService.processQueueOnceForTesting();

        ScanJob unchangedJob = delayedService.getJobForTesting(jobId);
        assertEquals(ScanJobStatus.QUEUED, unchangedJob.getStatus());
        assertEquals(1, unchangedJob.getAttempts());
        verify(activeScanService, times(1)).startActiveScanJob(anyString(), anyString(), any());
    }

    @Test
    void retryBudgetIsAppliedPerScanType() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(3, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any()))
                .thenThrow(new ZapApiException("active boom", new RuntimeException("active boom")));
        when(spiderScanService.startSpiderScanJob(anyString()))
                .thenThrow(new ZapApiException("spider boom", new RuntimeException("spider boom")));

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider"));

        policyService.processQueueOnceForTesting();
        policyService.processQueueOnceForTesting();

        ScanJob activeJob = policyService.getJobForTesting(activeJobId);
        ScanJob spiderJob = policyService.getJobForTesting(spiderJobId);

        assertEquals(ScanJobStatus.FAILED, activeJob.getStatus());
        assertEquals(3, activeJob.getAttempts());
        assertEquals(ScanJobStatus.FAILED, spiderJob.getStatus());
        assertEquals(2, spiderJob.getAttempts());
        verify(activeScanService, times(3)).startActiveScanJob(anyString(), anyString(), any());
        verify(spiderScanService, times(2)).startSpiderScanJob(anyString());
    }

    @Test
    void queuedJobsInheritTypeSpecificMaxAttemptDefaults() {
        ScanJobQueueService policyService = newServiceWithPolicies(
                new ScanJobQueueService.RetryPolicy(4, 0, 0, 1.0),
                new ScanJobQueueService.RetryPolicy(2, 0, 0, 1.0)
        );
        when(activeScanService.startActiveScanJob(anyString(), anyString(), any())).thenReturn("A-1");
        when(spiderScanService.startSpiderScanJob(anyString())).thenReturn("S-1");

        String activeJobId = extractJobId(policyService.queueActiveScan("http://example.com/active", "true", null));
        String spiderJobId = extractJobId(policyService.queueSpiderScan("http://example.com/spider"));

        ScanJob activeJob = policyService.getJobForTesting(activeJobId);
        ScanJob spiderJob = policyService.getJobForTesting(spiderJobId);

        assertEquals(4, activeJob.getMaxAttempts());
        assertEquals(2, spiderJob.getMaxAttempts());
    }

    private String extractJobId(String response) {
        for (String line : response.split("\\R")) {
            if (line.startsWith("Job ID: ")) {
                return line.substring("Job ID: ".length()).trim();
            }
        }
        fail("Unable to extract job ID from response: " + response);
        return null;
    }
}
