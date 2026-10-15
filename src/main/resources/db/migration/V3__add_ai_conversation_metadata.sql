ALTER TABLE chat_message ADD COLUMN source_type TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE chat_message ADD COLUMN conversation_id TEXT;
ALTER TABLE chat_message ADD COLUMN reply_kind TEXT NOT NULL DEFAULT 'LEGACY'
    CHECK (reply_kind IN ('AI','FALLBACK','BUSY','RATE_LIMITED','SERVICE_UNAVAILABLE','INPUT_TOO_LONG','LEGACY'));
ALTER TABLE chat_message ADD COLUMN in_reply_to_message_id TEXT;
ALTER TABLE chat_message ADD COLUMN correlation_id TEXT;
ALTER TABLE chat_message ADD COLUMN received_at TIMESTAMPTZ;
ALTER TABLE chat_message ADD COLUMN received_sequence BIGINT;
CREATE INDEX chat_private_history_idx ON chat_message (user_id, received_at DESC, received_sequence DESC)
    WHERE source_type = 'USER';
CREATE INDEX chat_reply_pair_idx ON chat_message (user_id, in_reply_to_message_id, correlation_id)
    WHERE direction = 'OUTBOUND' AND reply_kind = 'AI';
