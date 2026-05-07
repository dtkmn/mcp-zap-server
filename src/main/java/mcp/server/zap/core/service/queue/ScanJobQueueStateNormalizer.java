package mcp.server.zap.core.service.queue;

import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ScanJobQueueStateNormalizer {
    private static final Comparator<ScanJob> JOB_CREATION_ORDER =
            Comparator.comparing(ScanJob::getCreatedAt).thenComparing(ScanJob::getId);

    public ScanJobQueueState normalize(List<ScanJob> persistedJobs) {
        Map<String, ScanJob> restoredJobs = new LinkedHashMap<>();
        Deque<String> restoredQueuedJobIds = new ArrayDeque<>();
        boolean normalized = false;
        int repairedRunningJobs = 0;

        if (persistedJobs == null || persistedJobs.isEmpty()) {
            return new ScanJobQueueState(restoredJobs, restoredQueuedJobIds, false, 0);
        }

        ArrayList<ScanJob> orderedJobs = new ArrayList<>(persistedJobs);
        orderedJobs.sort(JOB_CREATION_ORDER);
        for (ScanJob job : orderedJobs) {
            if (job == null || !isRestorable(job)) {
                normalized = true;
                continue;
            }
            restoredJobs.put(job.getId(), job);
        }

        List<ScanJob> queuedJobs = restoredJobs.values().stream()
                .filter(job -> job.getStatus() == ScanJobStatus.QUEUED)
                .sorted((left, right) -> {
                    int leftPosition = left.getQueuePosition() > 0 ? left.getQueuePosition() : Integer.MAX_VALUE;
                    int rightPosition = right.getQueuePosition() > 0 ? right.getQueuePosition() : Integer.MAX_VALUE;
                    int compare = Integer.compare(leftPosition, rightPosition);
                    if (compare != 0) {
                        return compare;
                    }
                    return JOB_CREATION_ORDER.compare(left, right);
                })
                .toList();

        int expectedQueuePosition = 1;
        for (ScanJob queuedJob : queuedJobs) {
            if (queuedJob.getQueuePosition() != expectedQueuePosition) {
                normalized = true;
            }
            queuedJob.assignQueuePosition(expectedQueuePosition);
            restoredQueuedJobIds.addLast(queuedJob.getId());
            expectedQueuePosition += 1;
        }

        List<ScanJob> nonQueuedJobs = restoredJobs.values().stream()
                .filter(job -> job.getStatus() != ScanJobStatus.QUEUED)
                .sorted(JOB_CREATION_ORDER)
                .toList();
        for (ScanJob nonQueuedJob : nonQueuedJobs) {
            if (nonQueuedJob.getQueuePosition() != 0) {
                normalized = true;
                nonQueuedJob.assignQueuePosition(0);
            }
        }

        for (ScanJob job : restoredJobs.values()) {
            if (job.getStatus() == ScanJobStatus.RUNNING && !hasText(job.getZapScanId())) {
                job.markFailed("Missing ZAP scan ID while restoring durable scan job state");
                repairedRunningJobs += 1;
            }
        }
        if (repairedRunningJobs > 0) {
            normalized = true;
        }

        return new ScanJobQueueState(restoredJobs, restoredQueuedJobIds, normalized, repairedRunningJobs);
    }

    public List<ScanJob> storedJobsOf(Map<String, ScanJob> jobState, Deque<String> queuedState) {
        assignQueuePositions(jobState, queuedState);
        return jobState.values().stream()
                .sorted(JOB_CREATION_ORDER)
                .toList();
    }

    private void assignQueuePositions(Map<String, ScanJob> jobState, Deque<String> queuedState) {
        Set<String> queuedIds = new HashSet<>();
        int position = 1;
        for (String queuedJobId : queuedState) {
            ScanJob job = jobState.get(queuedJobId);
            if (job == null || job.getStatus() != ScanJobStatus.QUEUED) {
                continue;
            }
            job.assignQueuePosition(position++);
            queuedIds.add(queuedJobId);
        }

        for (ScanJob job : jobState.values()) {
            if (!queuedIds.contains(job.getId())) {
                job.assignQueuePosition(0);
            }
        }
    }

    private boolean isRestorable(ScanJob job) {
        return hasText(job.getId())
                && job.getType() != null
                && job.getParameters() != null
                && job.getCreatedAt() != null
                && job.getStatus() != null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
