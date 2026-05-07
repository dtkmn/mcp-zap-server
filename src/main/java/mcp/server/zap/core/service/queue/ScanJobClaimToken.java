package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;

import java.util.Objects;

public record ScanJobClaimToken(String ownerId, String fenceId) {

    public static ScanJobClaimToken from(ScanJob job) {
        return new ScanJobClaimToken(job.getClaimOwnerId(), job.getClaimFenceId());
    }

    public boolean matches(ScanJob job) {
        return job != null
                && Objects.equals(ownerId, job.getClaimOwnerId())
                && Objects.equals(fenceId, job.getClaimFenceId());
    }
}
