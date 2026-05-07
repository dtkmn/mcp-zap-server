ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS claim_fence_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS scan_jobs_claim_fence_id_idx
    ON scan_jobs (claim_fence_id);
