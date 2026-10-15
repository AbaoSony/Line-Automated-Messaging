package com.example.line.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 在统一删除事务中清理历史事件及历史审计；本次删除操作的新审计在事务完成后另行保留。 */
@Component
class LogDataPurger implements UserDataPurger {
    private final JdbcTemplate jdbc;

    LogDataPurger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 使用业务事务删除所有用户存储，避免独立日志连接池破坏跨删除器的原子性。
     * @param userId 完整用户标识
     * @throws org.springframework.dao.DataAccessException 删除失败时抛出并回滚
     */
    @Override
    public void purge(String userId) {
        jdbc.update("DELETE FROM event_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM audit_log WHERE target_user_id = ?", userId);
    }
}
