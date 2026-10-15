CREATE TABLE event_log (
    id UUID PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL,
    event TEXT NOT NULL,
    level TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    webhook_event_id TEXT,
    user_id TEXT,
    outcome TEXT CHECK (outcome IN ('SUCCESS', 'FAILED', 'SKIPPED')),
    duration_ms BIGINT,
    http_status INTEGER,
    error_type TEXT,
    message_type TEXT,
    model TEXT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    detail JSONB NOT NULL,
    service TEXT NOT NULL,
    env TEXT NOT NULL,
    version TEXT NOT NULL,
    instance TEXT NOT NULL
);
CREATE INDEX event_log_ts_idx ON event_log (ts);
CREATE INDEX event_log_trace_idx ON event_log (trace_id);
CREATE INDEX event_log_user_ts_idx ON event_log (user_id, ts);
CREATE INDEX event_log_event_ts_idx ON event_log (event, ts);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    event TEXT NOT NULL,
    operator TEXT NOT NULL,
    source_ip TEXT,
    target_user_id TEXT NOT NULL,
    trace_id TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED', 'SKIPPED')),
    detail JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX audit_log_created_idx ON audit_log (created_at);
CREATE INDEX audit_log_trace_idx ON audit_log (trace_id);
CREATE INDEX audit_log_target_created_idx ON audit_log (target_user_id, created_at);
