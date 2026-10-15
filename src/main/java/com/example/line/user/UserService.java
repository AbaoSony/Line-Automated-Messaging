package com.example.line.user;

import com.example.line.common.persistence.AsyncPersistence;
import com.example.line.common.logging.LogEvents;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 接收关注生命周期操作并异步更新用户表；取消关注只变更状态，不自动删除数据。 */
@Service
public class UserService implements UserDataPurger {
    private final UserRepository repository;
    private final AsyncPersistence persistence;

    /**
     * 注入用户仓储与失败隔离器。
     * @param repository PostgreSQL 用户仓储
     * @param persistence 独立有界异步落库入口
     */
    public UserService(UserRepository repository, AsyncPersistence persistence) {
        this.repository = repository;
        this.persistence = persistence;
    }

    /**
     * 异步记录关注或取消关注，不存在用户来源时安全跳过。
     * @param userId 完整用户标识，群组来源可能为空
     * @param following 是否处于关注状态
     * @param occurredAt LINE 事件时间，用于阻止旧事件覆盖新状态
     * @throws IllegalArgumentException 事件时间缺失时抛出
     */
    public void recordStatus(String userId, boolean following, Instant occurredAt) {
        if (userId == null || userId.isBlank()) return;
        if (occurredAt == null) throw new IllegalArgumentException("Missing lifecycle timestamp");
        persistence.submit(following ? LogEvents.USER_FOLLOW : LogEvents.USER_UNFOLLOW, userId,
                () -> repository.updateStatus(userId, following, occurredAt));
    }

    /**
     * 同步删除本模块的用户状态，供统一手动删除事务调用。
     * @param userId 完整用户标识
     * @throws org.springframework.dao.DataAccessException 数据库操作失败时抛出
     */
    @Override
    public void purge(String userId) { repository.delete(userId); }
}
