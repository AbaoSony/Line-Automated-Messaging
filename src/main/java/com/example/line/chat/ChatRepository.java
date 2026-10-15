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
