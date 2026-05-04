package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing execution contract for crawl and attack operations.
 */
public interface EngineScanExecution {

    String startSpiderScan(SpiderScanRequest request);

    String startSpiderScanAsUser(AuthenticatedSpiderScanRequest request);

    int readSpiderProgressPercent(String scanId);

    void stopSpiderScan(String scanId);

    String startActiveScan(ActiveScanRequest request);

    String startActiveScanAsUser(AuthenticatedActiveScanRequest request);

    int readActiveScanProgressPercent(String scanId);

    void stopActiveScan(String scanId);

    List<String> listActiveScanPolicyNames();

    List<PolicyCategorySnapshot> loadActiveScanPolicyCategories(String scanPolicyName);

    List<ScannerRuleSnapshot> loadActiveScanPolicyRules(String scanPolicyName);

    void updateActiveScanRuleState(ActiveScanRuleMutation mutation);

    record SpiderScanRequest(
            String targetUrl,
            int maxDepth,
            int threadCount,
            int maxDurationMinutes
    ) {
    }

    record AuthenticatedSpiderScanRequest(
            String contextId,
            String userId,
            String targetUrl,
            String maxChildren,
            String recurse,
            String subtreeOnly,
            int threadCount,
            int maxDurationMinutes
    ) {
    }

    record ActiveScanRequest(
            String targetUrl,
            String recurse,
            String policy,
            int maxDurationMinutes,
            int hostPerScan,
            int threadPerHost
    ) {
    }

    record AuthenticatedActiveScanRequest(
            String contextId,
            String userId,
            String targetUrl,
            String recurse,
            String policy,
            int maxDurationMinutes,
            int hostPerScan,
            int threadPerHost
    ) {
    }

    record ActiveScanRuleMutation(
            String scanPolicyName,
            List<String> ruleIds,
            Boolean enabled,
            String attackStrength,
            String alertThreshold
    ) {
        public ActiveScanRuleMutation {
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        }
    }

    record PolicyCategorySnapshot(
            String id,
            String name,
            boolean enabled,
            String attackStrength,
            String alertThreshold
    ) {
    }

    record ScannerRuleSnapshot(
            String id,
            String name,
            String policyId,
            boolean enabled,
            String attackStrength,
            String alertThreshold,
            String quality,
            String status,
            List<String> dependencies
    ) {
        public ScannerRuleSnapshot {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public boolean hasOverride() {
            return !enabled
                    || !"DEFAULT".equalsIgnoreCase(attackStrength)
                    || !"DEFAULT".equalsIgnoreCase(alertThreshold);
        }
    }
}
