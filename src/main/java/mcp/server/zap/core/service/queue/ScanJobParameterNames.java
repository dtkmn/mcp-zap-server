package mcp.server.zap.core.service.queue;

public final class ScanJobParameterNames {
    public static final String TARGET_URL = "targetUrl";
    public static final String RECURSE = "recurse";
    public static final String POLICY = "policy";
    public static final String CONTEXT_ID = "contextId";
    public static final String USER_ID = "userId";
    public static final String MAX_CHILDREN = "maxChildren";
    public static final String SUBTREE_ONLY = "subtreeOnly";
    public static final String REPLAY_OF_JOB_ID = "replayOfJobId";
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";

    private ScanJobParameterNames() {
    }
}
