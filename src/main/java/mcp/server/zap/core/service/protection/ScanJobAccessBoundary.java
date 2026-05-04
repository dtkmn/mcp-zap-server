package mcp.server.zap.core.service.protection;

import java.util.List;
import mcp.server.zap.core.model.ScanJob;

/**
 * Optional access boundary for queue job visibility and mutation.
 */
public interface ScanJobAccessBoundary {

    /**
     * Return true when the current requester can read or mutate the supplied job.
     */
    boolean canCurrentRequesterAccess(ScanJob job);

    /**
     * Filter a job snapshot to the subset visible to the current requester.
     */
    default List<ScanJob> filterVisibleJobs(List<ScanJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        return jobs.stream()
                .filter(this::canCurrentRequesterAccess)
                .toList();
    }
}
