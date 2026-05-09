package mcp.server.zap.extension.api.policy;

import java.util.Map;

/**
 * Extension API contract for evaluating a policy bundle without publishing enforcement audit events.
 */
public interface PolicyBundlePreviewer {

    Map<String, Object> preview(String policyBundle,
                                String toolName,
                                String target,
                                String evaluatedAt);
}
