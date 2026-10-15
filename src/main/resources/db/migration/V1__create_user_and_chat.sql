CREATE TABLE line_user (
    user_id TEXT PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('FOLLOWING', 'UNFOLLOWED')),
    followed_at TIMESTAMPTZ,
    unfollowed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id TEXT NOT NULL,
    direction VARCHAR(8) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    line_message_id TEXT,
    message_type TEXT NOT NULL,
    content JSONB NOT NULL,
    model_name TEXT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 不强制关注关系，允许用户未触发 follow、来源为群组等情况下独立保存消息。
CREATE UNIQUE INDEX chat_message_inbound_id ON chat_message (line_message_id)
    WHERE direction = 'INBOUND' AND line_message_id IS NOT NULL;
CREATE INDEX chat_message_user_created ON chat_message (user_id, created_at, id);
CREATE INDEX chat_message_created ON chat_message (created_at);
