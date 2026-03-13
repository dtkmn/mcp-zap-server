package mcp.server.zap.core.model;

public enum ScanJobType {
    ACTIVE_SCAN,
    ACTIVE_SCAN_AS_USER,
    SPIDER_SCAN,
    SPIDER_SCAN_AS_USER;

    /**
     * Return true when this type belongs to the active-scan family.
     */
    public boolean isActiveFamily() {
        return this == ACTIVE_SCAN || this == ACTIVE_SCAN_AS_USER;
    }

    /**
     * Return true when this type belongs to the spider-scan family.
     */
    public boolean isSpiderFamily() {
        return this == SPIDER_SCAN || this == SPIDER_SCAN_AS_USER;
    }
}
