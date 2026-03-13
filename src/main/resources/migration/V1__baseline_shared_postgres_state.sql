CREATE TABLE IF NOT EXISTS jwt_token_revocation (
    token_id VARCHAR(128) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS jwt_token_revocation_expires_at_idx
    ON jwt_token_revocation (expires_at);
