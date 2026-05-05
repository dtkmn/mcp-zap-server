package mcp.server.zap.core.service.policy;

import java.util.Map;

/**
 * Core contract for evaluating a policy bundle without publishing enforcement audit events.
 */
public interface PolicyBundlePreviewer {

    Map<String, Object> preview(String policyBundle,
                                String toolName,
                                String target,
                                String evaluatedAt);
}
