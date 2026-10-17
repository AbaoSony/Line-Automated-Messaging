-- 不推测旧数据的接收顺序；同事件时间且旧排序未知时保持原状态。
ALTER TABLE line_user ADD COLUMN lifecycle_received_at TIMESTAMPTZ;
ALTER TABLE line_user ADD COLUMN lifecycle_sequence BIGINT;
ALTER TABLE line_user ADD COLUMN lifecycle_event_id TEXT;
ALTER TABLE line_user ADD CONSTRAINT line_user_lifecycle_order_complete CHECK (
    (lifecycle_received_at IS NULL AND lifecycle_sequence IS NULL AND lifecycle_event_id IS NULL)
    OR (lifecycle_received_at IS NOT NULL AND lifecycle_sequence IS NOT NULL
        AND lifecycle_sequence >= 0 AND lifecycle_event_id IS NOT NULL)
);
