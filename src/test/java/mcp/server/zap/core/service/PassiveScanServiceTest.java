package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ApiResponseElement;
import org.zaproxy.clientapi.core.ApiResponseList;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.gen.Pscan;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PassiveScanServiceTest {
    private Pscan pscan;
    private PassiveScanService service;

    @BeforeEach
    void setup() {
        ClientApi clientApi = new ClientApi("localhost", 0);
        pscan = mock(Pscan.class);
        clientApi.pscan = pscan;
        service = new PassiveScanService(clientApi);
    }

    @Test
    void getPassiveScanStatusReturnsBacklogSummary() throws Exception {
        when(pscan.recordsToScan()).thenReturn(new ApiResponseElement("recordsToScan", "5"));
        when(pscan.scanOnlyInScope()).thenReturn(new ApiResponseElement("scanOnlyInScope", "false"));
        when(pscan.currentTasks()).thenReturn(taskList("task-1", "task-2"));

        String result = service.getPassiveScanStatus();

        assertTrue(result.contains("Completed: no"));
        assertTrue(result.contains("Records remaining: 5"));
        assertTrue(result.contains("Active tasks: 2"));
        assertTrue(result.contains("Scan only in scope: false"));
    }

    @Test
    void waitForPassiveScanCompletionReturnsCompletionMessage() throws Exception {
        when(pscan.recordsToScan())
                .thenReturn(new ApiResponseElement("recordsToScan", "2"))
                .thenReturn(new ApiResponseElement("recordsToScan", "0"));
        when(pscan.scanOnlyInScope()).thenReturn(new ApiResponseElement("scanOnlyInScope", "true"));
        when(pscan.currentTasks())
                .thenReturn(taskList("task-1"))
                .thenReturn(taskList());

        String result = service.waitForPassiveScanCompletion(1, 1);

        assertTrue(result.contains("Passive scan backlog drained."));
        assertTrue(result.contains("Completed: yes"));
        assertTrue(result.contains("Records remaining: 0"));
        assertTrue(result.contains("Scan only in scope: true"));
    }

    @Test
    void waitForPassiveScanCompletionTimesOutWhenBacklogPersists() throws Exception {
        when(pscan.recordsToScan()).thenReturn(new ApiResponseElement("recordsToScan", "3"));
        when(pscan.scanOnlyInScope()).thenReturn(new ApiResponseElement("scanOnlyInScope", "false"));
        when(pscan.currentTasks()).thenReturn(taskList("task-1"));

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
    void getPassiveScanStatusWrapsZapErrors() throws Exception {
        when(pscan.recordsToScan()).thenThrow(new ClientApiException("boom", null));

        assertThrowsExactly(ZapApiException.class, service::getPassiveScanStatus);
    }

    private ApiResponseList taskList(String... taskIds) {
        ApiResponse[] tasks = new ApiResponse[taskIds.length];
        for (int i = 0; i < taskIds.length; i++) {
            tasks[i] = new ApiResponseElement("task", taskIds[i]);
        }
        return new ApiResponseList("tasks", tasks);
    }
}
