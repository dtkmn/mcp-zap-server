ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS requester_id VARCHAR(128) NOT NULL DEFAULT 'anonymous';

ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE INDEX IF NOT EXISTS scan_jobs_requester_id_idx
    ON scan_jobs (requester_id);

CREATE UNIQUE INDEX IF NOT EXISTS scan_jobs_requester_id_idempotency_key_uidx
    ON scan_jobs (requester_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
