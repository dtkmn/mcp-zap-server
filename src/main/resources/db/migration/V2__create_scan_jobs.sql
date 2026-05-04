CREATE TABLE IF NOT EXISTS scan_jobs (
    job_id VARCHAR(64) PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    parameters_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    zap_scan_id VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_known_progress INTEGER NOT NULL,
    queue_position INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS scan_jobs_status_next_attempt_idx
    ON scan_jobs (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS scan_jobs_created_at_idx
    ON scan_jobs (created_at);

CREATE INDEX IF NOT EXISTS scan_jobs_status_queue_position_idx
    ON scan_jobs (status, queue_position);
