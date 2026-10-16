-- 成功历史与原始消息分离；组合外键防止跨用户、跨会话或错误方向的关联。
ALTER TABLE chat_message ADD CONSTRAINT chat_message_turn_scope
    UNIQUE (id,user_id,conversation_id,direction,message_type);

CREATE TABLE ai_turn (
    turn_id UUID PRIMARY KEY,
    send_attempt_id UUID NOT NULL UNIQUE,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL CHECK (conversation_id=user_id),
    outbound_message_id BIGINT NOT NULL UNIQUE,
    outbound_direction TEXT NOT NULL DEFAULT 'OUTBOUND' CHECK (outbound_direction='OUTBOUND'),
    message_type TEXT NOT NULL DEFAULT 'TEXT' CHECK (message_type='TEXT'),
    first_received_at TIMESTAMPTZ NOT NULL,
    first_received_sequence BIGINT NOT NULL CHECK (first_received_sequence>0),
    accepted_at TIMESTAMPTZ NOT NULL,
    member_count INTEGER NOT NULL CHECK (member_count>0),
    UNIQUE (turn_id,user_id,conversation_id),
    FOREIGN KEY (outbound_message_id,user_id,conversation_id,outbound_direction,message_type)
        REFERENCES chat_message(id,user_id,conversation_id,direction,message_type) ON DELETE CASCADE
);
CREATE TABLE ai_turn_member (
    turn_id UUID NOT NULL,
    member_order INTEGER NOT NULL CHECK (member_order>0),
    inbound_message_id BIGINT NOT NULL,
    user_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    inbound_direction TEXT NOT NULL DEFAULT 'INBOUND' CHECK (inbound_direction='INBOUND'),
    message_type TEXT NOT NULL DEFAULT 'TEXT' CHECK (message_type='TEXT'),
    webhook_event_id TEXT NOT NULL CHECK (btrim(webhook_event_id)<>''),
    trace_id TEXT,
    PRIMARY KEY (turn_id,member_order),
    UNIQUE (conversation_id,inbound_message_id),
    FOREIGN KEY (turn_id,user_id,conversation_id) REFERENCES ai_turn(turn_id,user_id,conversation_id) ON DELETE CASCADE,
    FOREIGN KEY (inbound_message_id,user_id,conversation_id,inbound_direction,message_type)
        REFERENCES chat_message(id,user_id,conversation_id,direction,message_type) ON DELETE CASCADE
);
CREATE INDEX ai_turn_history ON ai_turn(user_id,conversation_id,first_received_at,first_received_sequence);
CREATE INDEX ai_turn_accepted ON ai_turn(accepted_at);
CREATE INDEX ai_turn_member_inbound ON ai_turn_member(inbound_message_id);

-- 仅收集具有可核对业务来源的候选，不用相邻行推测。临时表随迁移事务结束销毁。
CREATE TEMP TABLE p2_turn_candidates ON COMMIT DROP AS
SELECT o.id AS outbound_id,i.id AS inbound_id,1::BIGINT AS member_order,1::INTEGER AS expected,
       NULL::UUID AS old_turn_id,NULL::TEXT AS trace_id
FROM chat_message o JOIN chat_message i
  ON i.line_message_id=o.in_reply_to_message_id AND i.correlation_id=o.correlation_id
 AND i.user_id=o.user_id AND i.conversation_id=o.conversation_id
WHERE o.direction='OUTBOUND' AND o.reply_kind='AI' AND o.source_type='USER'
 AND i.direction='INBOUND' AND i.source_type='USER' AND i.reply_kind='LEGACY'
 AND o.conversation_id=o.user_id AND o.message_type='TEXT' AND i.message_type='TEXT'
 AND jsonb_typeof(o.content->'text')='string' AND btrim(o.content->>'text')<>''
 AND jsonb_typeof(i.content->'text')='string' AND btrim(i.content->>'text')<>''
 AND btrim(i.line_message_id)<>'' AND btrim(i.correlation_id)<>''
 AND i.received_at IS NOT NULL AND i.received_sequence>0
 AND o.received_at=i.received_at AND o.received_sequence=i.received_sequence
 AND NOT (o.content ? 'ai_turn');

-- 第三/四阶段的过渡 JSON 只有完整匹配全部原始成员时才升级；畸形字段安全排除。
INSERT INTO p2_turn_candidates
SELECT o.id,i.id,e.ordinality,jsonb_array_length(o.content->'ai_turn'->'members'),
       CASE WHEN pg_input_is_valid(o.content->'ai_turn'->>'turn_id','uuid')
            THEN (o.content->'ai_turn'->>'turn_id')::UUID END,
       NULLIF(e.value->>'traceId','')
FROM chat_message o
CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(o.content->'ai_turn'->'members')='array'
    THEN o.content->'ai_turn'->'members' ELSE '[]'::jsonb END) WITH ORDINALITY e(value,ordinality)
JOIN chat_message i ON i.line_message_id=e.value->>'messageId' AND i.user_id=o.user_id
 AND i.conversation_id=o.conversation_id AND i.correlation_id=e.value->>'eventId'
WHERE o.direction='OUTBOUND' AND o.reply_kind='AI' AND o.source_type='USER'
 AND o.conversation_id=o.user_id AND o.message_type='TEXT' AND o.in_reply_to_message_id IS NULL
 AND jsonb_typeof(o.content->'text')='string' AND btrim(o.content->>'text')<>''
 AND i.direction='INBOUND' AND i.source_type='USER' AND i.reply_kind='LEGACY' AND i.message_type='TEXT'
 AND i.user_id=e.value->>'userId' AND btrim(i.line_message_id)<>'' AND btrim(i.correlation_id)<>''
 AND jsonb_typeof(i.content->'text')='string' AND btrim(i.content->>'text')<>''
 AND i.content->>'text'=e.value->>'text' AND i.received_sequence>0
 AND i.received_sequence=CASE WHEN pg_input_is_valid(e.value->>'sequence','bigint') THEN (e.value->>'sequence')::BIGINT END
 AND i.created_at=CASE WHEN pg_input_is_valid(e.value->>'occurredAt','timestamptz') THEN (e.value->>'occurredAt')::TIMESTAMPTZ END
 AND i.received_at=CASE WHEN pg_input_is_valid(e.value->>'receivedAt','timestamptz') THEN (e.value->>'receivedAt')::TIMESTAMPTZ END
 AND pg_input_is_valid(o.content->'ai_turn'->>'turn_id','uuid');

CREATE TEMP TABLE p2_valid_turns ON COMMIT DROP AS
SELECT c.outbound_id,COALESCE(min(c.old_turn_id::TEXT)::UUID,gen_random_uuid()) AS turn_id,
       gen_random_uuid() AS send_attempt_id,count(*)::INTEGER AS member_count
FROM p2_turn_candidates c
GROUP BY c.outbound_id
HAVING count(*)=max(c.expected) AND count(DISTINCT c.inbound_id)=count(*)
 AND min(c.member_order)=1 AND max(c.member_order)=count(*)
 AND NOT EXISTS (SELECT 1 FROM p2_turn_candidates x WHERE x.inbound_id IN
       (SELECT inbound_id FROM p2_turn_candidates y WHERE y.outbound_id=c.outbound_id)
       AND x.outbound_id<>c.outbound_id)
 AND NOT EXISTS (SELECT 1 FROM p2_turn_candidates x WHERE x.old_turn_id IS NOT NULL
       AND x.old_turn_id IN (SELECT old_turn_id FROM p2_turn_candidates y WHERE y.outbound_id=c.outbound_id)
       AND x.outbound_id<>c.outbound_id)
 AND NOT EXISTS (SELECT 1 FROM p2_turn_candidates x JOIN chat_message i ON i.id=x.inbound_id
       JOIN p2_turn_candidates y ON y.outbound_id=x.outbound_id AND y.member_order=x.member_order+1
       JOIN chat_message j ON j.id=y.inbound_id
       WHERE x.outbound_id=c.outbound_id AND (i.received_at,i.received_sequence)>=(j.received_at,j.received_sequence))
 AND NOT EXISTS (SELECT 1 FROM chat_message o JOIN p2_turn_candidates x ON x.outbound_id=o.id AND x.member_order=1
       JOIN chat_message i ON i.id=x.inbound_id WHERE o.id=c.outbound_id
       AND (o.received_at IS DISTINCT FROM i.received_at OR o.received_sequence IS DISTINCT FROM i.received_sequence
            OR o.correlation_id IS DISTINCT FROM i.correlation_id));

INSERT INTO ai_turn(turn_id,send_attempt_id,user_id,conversation_id,outbound_message_id,
                    first_received_at,first_received_sequence,accepted_at,member_count)
SELECT v.turn_id,v.send_attempt_id,o.user_id,o.conversation_id,o.id,i.received_at,i.received_sequence,o.created_at,v.member_count
FROM p2_valid_turns v JOIN chat_message o ON o.id=v.outbound_id
JOIN p2_turn_candidates c ON c.outbound_id=o.id AND c.member_order=1 JOIN chat_message i ON i.id=c.inbound_id;
INSERT INTO ai_turn_member(turn_id,member_order,inbound_message_id,user_id,conversation_id,webhook_event_id,trace_id)
SELECT v.turn_id,c.member_order,i.id,i.user_id,i.conversation_id,i.correlation_id,c.trace_id
FROM p2_valid_turns v JOIN p2_turn_candidates c ON c.outbound_id=v.outbound_id JOIN chat_message i ON i.id=c.inbound_id;
