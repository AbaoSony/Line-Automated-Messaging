package com.example.line.user;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 使用 PostgreSQL upsert 更新用户状态，重复关注与乱序异步任务不会覆盖更新的生命周期。 */
@Repository
class UserRepository {
    private final JdbcTemplate jdbc;

    UserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean updateStatus(String userId, boolean following, LifecycleOrder order) {
        Instant at=order.occurredAt();
        return jdbc.update("""
                INSERT INTO line_user (user_id, status, followed_at, unfollowed_at, created_at, updated_at,
                    lifecycle_received_at,lifecycle_sequence,lifecycle_event_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    followed_at = CASE WHEN EXCLUDED.status = 'FOLLOWING' THEN EXCLUDED.followed_at
                                       ELSE line_user.followed_at END,
                    unfollowed_at = CASE WHEN EXCLUDED.status = 'UNFOLLOWED' THEN EXCLUDED.unfollowed_at
                                         ELSE NULL END,
                    updated_at = EXCLUDED.updated_at,
                    lifecycle_received_at=EXCLUDED.lifecycle_received_at,
                    lifecycle_sequence=EXCLUDED.lifecycle_sequence,
                    lifecycle_event_id=EXCLUDED.lifecycle_event_id
                WHERE line_user.lifecycle_event_id IS DISTINCT FROM EXCLUDED.lifecycle_event_id
                  AND (EXCLUDED.updated_at > line_user.updated_at OR
                    (EXCLUDED.updated_at = line_user.updated_at AND NOT ?
                     AND line_user.lifecycle_received_at IS NOT NULL
                     AND (EXCLUDED.lifecycle_received_at,EXCLUDED.lifecycle_sequence) >
                         (line_user.lifecycle_received_at,line_user.lifecycle_sequence)))
                """, userId, following ? "FOLLOWING" : "UNFOLLOWED", following ? Timestamp.from(at) : null,
                following ? null : Timestamp.from(at), Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(order.receivedAt()),order.sequence(),order.eventId(),order.redelivery())==1;
    }

    boolean active(String userId) {
        // 未收到 follow 的用户仍可聊天；只有权威表明确取消关注才拒绝 AI 准入。
        return jdbc.query("SELECT status FROM line_user WHERE user_id=?",(rs,row) -> rs.getString(1),userId)
                .stream().noneMatch("UNFOLLOWED"::equals);
    }

    void delete(String userId) { jdbc.update("DELETE FROM line_user WHERE user_id = ?", userId); }
}
