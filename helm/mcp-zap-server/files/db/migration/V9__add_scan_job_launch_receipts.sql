-- Launch identity outlives a renewable worker claim and prevents ambiguous starts being replayed.
ALTER TABLE scan_jobs ADD COLUMN launch_attempt_json TEXT;
