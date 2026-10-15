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

    void updateStatus(String userId, boolean following, Instant at) {
        jdbc.update("""
                INSERT INTO line_user (user_id, status, followed_at, unfollowed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    status = EXCLUDED.status,
                    followed_at = CASE WHEN EXCLUDED.status = 'FOLLOWING' THEN EXCLUDED.followed_at
                                       ELSE line_user.followed_at END,
                    unfollowed_at = CASE WHEN EXCLUDED.status = 'UNFOLLOWED' THEN EXCLUDED.unfollowed_at
                                         ELSE NULL END,
                    updated_at = EXCLUDED.updated_at
                WHERE EXCLUDED.updated_at >= line_user.updated_at
                """, userId, following ? "FOLLOWING" : "UNFOLLOWED", following ? Timestamp.from(at) : null,
                following ? null : Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }

    void delete(String userId) { jdbc.update("DELETE FROM line_user WHERE user_id = ?", userId); }
}
