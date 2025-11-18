package mcp.server.zap.configuration;

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

    @Test
    void canSetCustomValues() {
        ScanLimitProperties props = new ScanLimitProperties();
        props.setMaxActiveScanDurationInMins(45);
        props.setThreadPerHost(15);
        props.setSpiderMaxDepth(20);
        
        assertEquals(45, props.getMaxActiveScanDurationInMins());
        assertEquals(15, props.getThreadPerHost());
        assertEquals(20, props.getSpiderMaxDepth());
    }
}
