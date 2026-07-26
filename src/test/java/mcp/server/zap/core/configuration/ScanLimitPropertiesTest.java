package mcp.server.zap.core.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanLimitPropertiesTest {

    @Test
    void hasReasonableDefaults() {
        ScanLimitProperties defaultProps = new ScanLimitProperties();
        assertEquals(30, defaultProps.getMaxActiveScanDurationInMins());
        assertEquals(15, defaultProps.getMaxSpiderScanDurationInMins());
        assertEquals(10, defaultProps.getThreadPerHost());
        assertEquals(5, defaultProps.getSpiderThreadCount());
    }

}
