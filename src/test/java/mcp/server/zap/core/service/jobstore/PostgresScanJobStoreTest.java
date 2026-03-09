package mcp.server.zap.core.service.jobstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import mcp.server.zap.core.configuration.ScanJobStoreProperties;
import mcp.server.zap.core.model.ScanJob;
import mcp.server.zap.core.model.ScanJobStatus;
import mcp.server.zap.core.model.ScanJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresScanJobStoreTest {

    @Test
    void upsertAllSupportsInstantFieldsWhenConstructedWithRawObjectMapper() {
        ScanJobStoreProperties.Postgres properties = new ScanJobStoreProperties.Postgres();
        properties.setUrl("jdbc:postgresql://127.0.0.1:1/mcp_zap");
        properties.setFailFast(false);

        PostgresScanJobStore store = new PostgresScanJobStore(properties, new ObjectMapper());
        ScanJob job = ScanJob.restore(
                "job-1",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com", "recurse", "true", "policy", ""),
                Instant.now(),
                3,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0
        );

        assertDoesNotThrow(() -> store.upsertAll(List.of(job)));
    }

    @Test
    void normalizeForCommitSupportsImmutableUpdaterResults() {
        ScanJob laterJob = ScanJob.restore(
                "job-2",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/2", "recurse", "true", "policy", ""),
                Instant.parse("2026-03-09T00:00:02Z"),
                3,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0
        );
        ScanJob earlierJob = ScanJob.restore(
                "job-1",
                ScanJobType.ACTIVE_SCAN,
                Map.of("targetUrl", "http://example.com/1", "recurse", "true", "policy", ""),
                Instant.parse("2026-03-09T00:00:01Z"),
                3,
                ScanJobStatus.QUEUED,
                0,
                null,
                null,
                null,
                null,
                null,
                0,
                0
        );

        List<ScanJob> committedJobs = PostgresScanJobStore.normalizeForCommit(List.of(laterJob, earlierJob));

        assertEquals(List.of("job-1", "job-2"), committedJobs.stream().map(ScanJob::getId).toList());
    }
}
