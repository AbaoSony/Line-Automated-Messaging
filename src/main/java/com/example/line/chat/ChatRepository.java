package com.example.line.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 封装 chat_message SQL，使用唯一索引兜底入站幂等，并支持有序历史与分批清理。 */
@Repository
class ChatRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ChatRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void insert(String userId, String direction, String lineMessageId, String messageType,
                String content, Instant createdAt) {
        jdbc.update("""
                INSERT INTO chat_message (user_id, direction, line_message_id, message_type, content, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (line_message_id) WHERE direction = 'INBOUND' AND line_message_id IS NOT NULL
                DO NOTHING
                """, userId, direction, lineMessageId, messageType, content, timestamp(createdAt));
    }

    void insert(String userId, String direction, String messageId, String type, String content, Instant time, ChatMetadata meta) {
        jdbc.update("""
                INSERT INTO chat_message (user_id,direction,line_message_id,message_type,content,created_at,
                source_type,conversation_id,reply_kind,in_reply_to_message_id,correlation_id,received_at,received_sequence,
                model_name,input_tokens,output_tokens,latency_ms)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (line_message_id) WHERE direction = 'INBOUND' AND line_message_id IS NOT NULL DO NOTHING
                """, userId, direction, messageId, type, content, timestamp(time), meta.sourceType(), meta.conversationId(),
                meta.replyKind(), meta.inReplyToMessageId(), meta.correlationId(), timestamp(meta.receivedAt()),
                meta.receivedSequence(), meta.model(), meta.inputTokens(), meta.outputTokens(), meta.latencyMs());
    }

    PrivateHistory privateHistory(String user, String currentId, Instant receivedAt, long sequence, int limit) {
        var rows=jdbc.queryForList("""
                WITH candidates AS (
                  SELECT t.*,o.content AS output_content FROM ai_turn t JOIN chat_message o ON o.id=t.outbound_message_id
                  WHERE t.user_id=? AND t.conversation_id=? AND o.source_type='USER' AND o.reply_kind='AI'
                    AND jsonb_typeof(o.content->'text')='string' AND btrim(o.content->>'text')<>''
                    AND (SELECT count(*) FROM ai_turn_member m WHERE m.turn_id=t.turn_id)=t.member_count
                    AND (SELECT max(member_order) FROM ai_turn_member m WHERE m.turn_id=t.turn_id)=t.member_count
                    AND EXISTS (SELECT 1 FROM ai_turn_member m JOIN chat_message i ON i.id=m.inbound_message_id
                      WHERE m.turn_id=t.turn_id AND m.member_order=1 AND i.received_at=t.first_received_at
                        AND i.received_sequence=t.first_received_sequence)
                    AND NOT EXISTS (SELECT 1 FROM ai_turn_member m JOIN chat_message i ON i.id=m.inbound_message_id
                      WHERE m.turn_id=t.turn_id AND (i.line_message_id=? OR i.source_type<>'USER' OR i.reply_kind<>'LEGACY'
                        OR i.correlation_id IS DISTINCT FROM m.webhook_event_id OR i.received_at IS NULL OR i.received_sequence IS NULL
                        OR i.received_sequence<1 OR i.line_message_id IS NULL OR btrim(i.line_message_id)=''
                        OR jsonb_typeof(i.content->'text') IS DISTINCT FROM 'string' OR btrim(i.content->>'text')=''
                        OR (i.received_at,i.received_sequence)>=(?::timestamptz,?)))
                    AND NOT EXISTS (SELECT 1 FROM ai_turn_member m JOIN chat_message i ON i.id=m.inbound_message_id
                      JOIN ai_turn_member n ON n.turn_id=m.turn_id AND n.member_order=m.member_order+1
                      JOIN chat_message j ON j.id=n.inbound_message_id
                      WHERE m.turn_id=t.turn_id AND (i.received_at,i.received_sequence)>=(j.received_at,j.received_sequence))
                  ORDER BY t.first_received_at DESC,t.first_received_sequence DESC,t.turn_id DESC LIMIT ?
                ), bounded AS (
                  SELECT *,sum(member_count+1) OVER (ORDER BY first_received_at DESC,first_received_sequence DESC,turn_id DESC) AS scanned
                  FROM candidates
                )
                SELECT t.turn_id,t.output_content,m.member_order,m.trace_id,i.line_message_id,i.correlation_id,
                  i.content,i.created_at,i.received_at,i.received_sequence
                FROM bounded t JOIN ai_turn_member m ON m.turn_id=t.turn_id JOIN chat_message i ON i.id=m.inbound_message_id
                WHERE t.scanned<=? ORDER BY t.first_received_at,t.first_received_sequence,t.turn_id,m.member_order
                """,user,user,currentId,Timestamp.from(receivedAt),sequence,limit,limit);
        var grouped=new java.util.LinkedHashMap<java.util.UUID,java.util.ArrayList<PrivateHistory.Member>>();
        var outputs=new java.util.HashMap<java.util.UUID,String>();
        for(var row:rows) {
            var id=(java.util.UUID)row.get("turn_id");
            grouped.computeIfAbsent(id,ignored -> new java.util.ArrayList<>()).add(new PrivateHistory.Member(
                    (String)row.get("line_message_id"),(String)row.get("correlation_id"),text(row.get("content")),
                    ((Timestamp)row.get("created_at")).toInstant(),((Timestamp)row.get("received_at")).toInstant(),
                    ((Number)row.get("received_sequence")).longValue(),(String)row.get("trace_id")));
            outputs.put(id,text(row.get("output_content")));
        }
        var turns=grouped.entrySet().stream().map(entry -> new PrivateHistory.Turn(entry.getKey(),entry.getValue(),outputs.get(entry.getKey()))).toList();
        var last=lastPrivateInbound(user,currentId,receivedAt,sequence);
        return new PrivateHistory(turns,last==null?null:last.occurredAt());
    }

    private String text(Object content) {
        try { return mapper.readTree(content.toString()).path("text").asText(); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid stored conversation content", failure); }
    }

    PrivateHistory.InputTime lastPrivateInbound(String user,String currentId,Instant received,long sequence) {
        var times=jdbc.query("""
                SELECT created_at,received_at,received_sequence FROM chat_message WHERE user_id=? AND source_type='USER'
                AND conversation_id=? AND direction='INBOUND' AND line_message_id<>?
                AND (received_at < ? OR (received_at = ? AND received_sequence < ?))
                ORDER BY received_at DESC,received_sequence DESC,id DESC LIMIT 1
                """,(row,index) -> new PrivateHistory.InputTime(row.getTimestamp(1).toInstant(),row.getTimestamp(2).toInstant(),row.getLong(3)),user,user,currentId,Timestamp.from(received),Timestamp.from(received),sequence);
        return times.isEmpty()?null:times.get(0);
    }

    List<ChatMessage> findRecent(String userId, int limit) {
        // 先选最新 N 条，再按时间正序返回，供后续模块按对话顺序构建上下文。
        return jdbc.query("""
                SELECT * FROM (
                    SELECT * FROM chat_message WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                ) recent ORDER BY created_at, id
                """, this::map, userId, limit);
    }

    @org.springframework.transaction.annotation.Transactional
    int deleteExpiredBatch(Instant cutoff, int batchSize) {
        int deleted=jdbc.update("""
                DELETE FROM chat_message WHERE id IN (
                    SELECT id FROM chat_message WHERE created_at < ? ORDER BY created_at, id LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
        // 入站过期会级联删除成员，清理同一批中的残缺轮次，剩余原始消息继续按各自保留期处理。
        jdbc.update("""
                DELETE FROM ai_turn WHERE turn_id IN (
                  SELECT t.turn_id FROM ai_turn t WHERE
                    (SELECT count(*) FROM ai_turn_member m WHERE m.turn_id=t.turn_id)<>t.member_count
                  ORDER BY t.accepted_at,t.turn_id LIMIT ?)
                """,batchSize);
        return deleted;
    }

    private static Timestamp timestamp(Instant time) {
        return time==null?null:Timestamp.from(time.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    void delete(String userId) { jdbc.update("DELETE FROM chat_message WHERE user_id = ?", userId); }

    private ChatMessage map(ResultSet row, int index) throws SQLException {
        try {
            return new ChatMessage(row.getLong("id"), row.getString("user_id"), row.getString("direction"),
                    row.getString("line_message_id"), row.getString("message_type"),
                    mapper.readTree(row.getString("content")), row.getString("model_name"),
                    row.getObject("input_tokens", Integer.class), row.getObject("output_tokens", Integer.class),
                    row.getObject("latency_ms", Long.class), row.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException failure) {
            throw new SQLException("Invalid stored chat JSON", failure);
        }
    }
}
