CREATE TABLE IF NOT EXISTS scan_history_entries (
    ledger_id VARCHAR(96) PRIMARY KEY,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL,
    engine_id VARCHAR(64) NOT NULL,
    target_kind VARCHAR(32),
    target_url TEXT,
    target_display_name TEXT,
    execution_mode VARCHAR(64),
    backend_reference VARCHAR(256),
    artifact_id VARCHAR(256),
    artifact_type VARCHAR(64),
    artifact_location TEXT,
    media_type VARCHAR(128),
    client_id VARCHAR(128),
    workspace_id VARCHAR(128),
    metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS scan_history_entries_recorded_at_idx
    ON scan_history_entries (recorded_at DESC);

CREATE INDEX IF NOT EXISTS scan_history_entries_type_status_idx
    ON scan_history_entries (evidence_type, status, recorded_at DESC);

CREATE INDEX IF NOT EXISTS scan_history_entries_workspace_idx
    ON scan_history_entries (workspace_id, recorded_at DESC);
