package mcp.server.zap.core.gateway;

import java.util.List;

/**
 * Gateway-facing execution contract for crawl and attack operations.
 */
public interface EngineScanExecution {

    String startSpiderScan(SpiderScanRequest request);

    default String startSpiderScan(SpiderScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startSpiderScan(request);
        launch.accepted(id);
        return id;
    }

    String startSpiderScanAsUser(AuthenticatedSpiderScanRequest request);

    default String startSpiderScanAsUser(AuthenticatedSpiderScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startSpiderScanAsUser(request);
        launch.accepted(id);
        return id;
    }

    int readSpiderProgressPercent(String scanId);

    void stopSpiderScan(String scanId);

    String startClientSpiderScan(ClientSpiderScanRequest request);

    default String startClientSpiderScan(ClientSpiderScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startClientSpiderScan(request);
        launch.accepted(id);
        return id;
    }

    int readClientSpiderProgressPercent(String scanId);

    void stopClientSpiderScan(String scanId);

    String startActiveScan(ActiveScanRequest request);

    default String startActiveScan(ActiveScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startActiveScan(request);
        launch.accepted(id);
        return id;
    }

    String startActiveScanAsUser(AuthenticatedActiveScanRequest request);

    default String startActiveScanAsUser(AuthenticatedActiveScanRequest request, NativeScanLaunch launch) {
        launch.beforeDispatch();
        String id = startActiveScanAsUser(request);
        launch.accepted(id);
        return id;
    }

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
            int maxDurationMinutes,
            int maxChildren
    ) {
        public SpiderScanRequest {
            if (maxDepth < 0 || maxChildren < 0) {
                throw new IllegalArgumentException("Spider depth and child limits must be zero (unlimited) or greater");
            }
        }

        public SpiderScanRequest(String targetUrl, int maxDepth, int threadCount, int maxDurationMinutes) {
            this(targetUrl, maxDepth, threadCount, maxDurationMinutes, 10);
        }
    }

    record AuthenticatedSpiderScanRequest(
            String contextId,
            String userId,
            String targetUrl,
            String maxChildren,
            String recurse,
            String subtreeOnly,
            int threadCount,
            int maxDurationMinutes,
            int maxDepth
    ) {
        public AuthenticatedSpiderScanRequest {
            if (maxDepth < 0) {
                throw new IllegalArgumentException("Spider depth must be zero (unlimited) or greater");
            }
        }

        public AuthenticatedSpiderScanRequest(String contextId, String userId, String targetUrl,
                                              String maxChildren, String recurse, String subtreeOnly,
                                              int threadCount, int maxDurationMinutes) {
            this(contextId, userId, targetUrl, maxChildren, recurse, subtreeOnly,
                    threadCount, maxDurationMinutes, 10);
        }
    }

    record ClientSpiderScanRequest(
            String targetUrl,
            int maxDepth,
            int maxDurationMinutes,
            String contextName,
            String userName
    ) {
        public ClientSpiderScanRequest(String targetUrl, int maxDepth, int maxDurationMinutes) {
            this(targetUrl, maxDepth, maxDurationMinutes, null, null);
        }
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
