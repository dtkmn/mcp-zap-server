package mcp.server.zap.model;

public enum ScanJobType {
    ACTIVE_SCAN,
    ACTIVE_SCAN_AS_USER,
    SPIDER_SCAN,
    SPIDER_SCAN_AS_USER;

    public boolean isActiveFamily() {
        return this == ACTIVE_SCAN || this == ACTIVE_SCAN_AS_USER;
    }

    public boolean isSpiderFamily() {
        return this == SPIDER_SCAN || this == SPIDER_SCAN_AS_USER;
    }
}
