package mcp.server.zap.core.service;

import mcp.server.zap.core.exception.ZapApiException;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess;
import mcp.server.zap.core.gateway.EnginePassiveScanAccess.PassiveScanSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PassiveScanServiceTest {
    private EnginePassiveScanAccess passiveScanAccess;
    private PassiveScanService service;

    @BeforeEach
    void setup() {
        passiveScanAccess = mock(EnginePassiveScanAccess.class);
        service = new PassiveScanService(passiveScanAccess);
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
    void getPassiveScanStatusWrapsZapErrors() throws Exception {
        when(passiveScanAccess.loadPassiveScanSnapshot())
                .thenThrow(new ZapApiException("boom", new RuntimeException("boom")));

        assertThrowsExactly(ZapApiException.class, service::getPassiveScanStatus);
    }
}
