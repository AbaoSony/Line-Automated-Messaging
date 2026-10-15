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
                """, userId, direction, lineMessageId, messageType, content, Timestamp.from(createdAt));
    }

    void insert(String userId, String direction, String messageId, String type, String content, Instant time, ChatMetadata meta) {
        jdbc.update("""
                INSERT INTO chat_message (user_id,direction,line_message_id,message_type,content,created_at,
                source_type,conversation_id,reply_kind,in_reply_to_message_id,correlation_id,received_at,received_sequence,
                model_name,input_tokens,output_tokens,latency_ms)
                VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (line_message_id) WHERE direction = 'INBOUND' AND line_message_id IS NOT NULL DO NOTHING
                """, userId, direction, messageId, type, content, Timestamp.from(time), meta.sourceType(), meta.conversationId(),
                meta.replyKind(), meta.inReplyToMessageId(), meta.correlationId(), Timestamp.from(meta.receivedAt()),
                meta.receivedSequence(), meta.model(), meta.inputTokens(), meta.outputTokens(), meta.latencyMs());
    }

    PrivateHistory privateHistory(String user, String currentId, Instant receivedAt, long sequence, int limit) {
        var rows = jdbc.queryForList("""
                SELECT * FROM chat_message WHERE user_id=? AND source_type='USER' AND conversation_id=?
                AND (received_at < ? OR (received_at = ? AND received_sequence < ?))
                AND (line_message_id IS NULL OR line_message_id <> ?)
                ORDER BY received_at DESC,received_sequence DESC,id DESC LIMIT ?
                """, user, user, Timestamp.from(receivedAt), Timestamp.from(receivedAt), sequence, currentId, limit);
        var inbound = new java.util.LinkedHashMap<String, java.util.Map<String,Object>>();
        Instant last = null;
        for (var row : rows) if ("INBOUND".equals(row.get("direction"))) {
            if (last == null) last = ((Timestamp) row.get("created_at")).toInstant();
            if ("TEXT".equals(row.get("message_type"))) inbound.put((String) row.get("line_message_id"), row);
        }
        var paired = new java.util.LinkedHashMap<String, PrivateHistory.Turn>();
        for (var row : rows) if ("OUTBOUND".equals(row.get("direction")) && "AI".equals(row.get("reply_kind"))) {
            var input = inbound.get(row.get("in_reply_to_message_id"));
            if (input == null || !java.util.Objects.equals(input.get("correlation_id"), row.get("correlation_id"))) continue;
            String id = (String) input.get("line_message_id");
            paired.putIfAbsent(id, new PrivateHistory.Turn(id, (String) input.get("correlation_id"),
                    text(input.get("content")), text(row.get("content")), ((Timestamp) input.get("created_at")).toInstant(),
                    ((Timestamp) input.get("received_at")).toInstant(), ((Number) input.get("received_sequence")).longValue()));
        }
        var turns = new java.util.ArrayList<>(paired.values());
        turns.sort(java.util.Comparator.comparing(PrivateHistory.Turn::receivedAt).thenComparingLong(PrivateHistory.Turn::sequence));
        return new PrivateHistory(List.copyOf(turns), last);
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

    int deleteExpiredBatch(Instant cutoff, int batchSize) {
        return jdbc.update("""
                DELETE FROM chat_message WHERE id IN (
                    SELECT id FROM chat_message WHERE created_at < ? ORDER BY created_at, id LIMIT ?
                )
                """, Timestamp.from(cutoff), batchSize);
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
