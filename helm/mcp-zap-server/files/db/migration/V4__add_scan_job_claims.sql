ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS claim_owner_id VARCHAR(128);

ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS claim_heartbeat_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS claim_expires_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS scan_jobs_claim_owner_id_idx
    ON scan_jobs (claim_owner_id);

CREATE INDEX IF NOT EXISTS scan_jobs_status_claim_expires_idx
    ON scan_jobs (status, claim_expires_at);
